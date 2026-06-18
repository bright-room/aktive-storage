# Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 14 code-review findings plus narrow `catch (Throwable)` to `catch (Exception)`, across the aktive-storage Kotlin library, as four themed PRs.

**Architecture:** Four phases = four PRs, merged in order ③→②→①→④ to minimise rebase churn. Each phase is its own branch off the latest `main`. TDD per fix: write the failing test, watch it fail, implement minimally, watch it pass, commit. ABI-affecting phases (①④) regenerate the binary-compatibility-validator baselines.

**Tech Stack:** Kotlin 2.4 / coroutines / kotlinx-io / Exposed 1.3 (JDBC) / AWS SDK for Kotlin (S3) / Scrimage 4.6 / JUnit5 + kotlinx-coroutines-test / Testcontainers (Postgres, MinIO) / binary-compatibility-validator 0.18.

---

## Conventions & Commands

- Unit tests (one module): `./gradlew :core:test` (also `:variant-scrimage:test`, `:storage-fs:test`, `:storage-s3:test`, `:metadata-exposed-jdbc:test`).
- One test class: `./gradlew :core:test --tests "net.brightroom.aktivestorage.VariantTest"`.
- Integration tests (need Docker): `./gradlew :metadata-exposed-jdbc:integrationTest`, `:storage-s3:integrationTest`, `:integration-tests:integrationTest`.
- ABI check / regen: `./gradlew apiCheck` (verify) · `./gradlew apiDump` (regenerate `<module>/api/<module>.api`).
- Format before commit: `./gradlew spotlessApply`.
- All production code is `explicitApi()` — new public symbols need explicit `public`.

## File Structure (created / modified across the whole effort)

- `core/.../Variant.kt` — modify: `Transform.Rotate` validation (①).
- `core/.../Ports.kt` — modify: add `MetadataStore.isVariantBlob`, document `insertVariant` duplicate contract (①); remove `StorageService.exists` (④).
- `core/.../AktiveStorage.kt` — modify: variant nesting guard + size guard + duplicate handling + `newBlob` factory (①); `owns()` helper + `resolveForDelivery` guard (②); attach NonCancellable compensation (③).
- `core/.../VariantExceptions.kt` — **create**: `DuplicateVariantException`, `VariantSourceTooLargeException` (①).
- `core/.../HmacReferenceSigner.kt` — modify: key-length `require` (②).
- `core/.../Spool.kt` — modify: `catch (Throwable)`→`catch (Exception)` (③).
- `variant-scrimage/.../ScrimageVariantProcessor.kt` — modify: single-axis resize, `Dispatchers.Default` (③/①).
- `metadata-exposed-jdbc/.../ExposedMetadataStore.kt` — modify: duplicate→`DuplicateVariantException`, `isVariantBlob`, length validation (①/③).
- `metadata-exposed-jdbc/.../Tables.kt` — modify: `filename` → `text` (③).
- `storage-s3/.../S3StorageService.kt` — modify: temp-leak fix + narrowing (③); remove `exists` (④).
- `storage-fs/.../FilesystemStorageService.kt` — modify: narrowing (③); path hardening (②); remove `exists` (④).
- Test fakes: `InMemoryMetadataStore` (+`isVariantBlob`), `InMemoryStorageService`/`AttachRollbackTest.FailingStorageService` (−`exists`).
- Many core tests + `EndToEndIT`: 32-byte signer keys (②).

---

# Phase / PR③ — 堅牢性 (merge first)

Branch: `fix/robustness` off `main`. No ABI impact.

### Task 3.1: Narrow `catch (Throwable)` → `catch (Exception)` in Spool & FS

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt:51`
- Modify: `storage-fs/src/main/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageService.kt:35`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/SpoolTest.kt`

- [ ] **Step 1: Add a test proving the Exception cleanup path still deletes the temp file**

Append to `SpoolTest.kt` (add imports `kotlinx.io.Buffer`, `kotlinx.io.RawSource`, `kotlinx.io.files.SystemFileSystem`, `kotlinx.io.files.SystemTemporaryDirectory`, `kotlin.test.assertEquals`, `kotlin.test.assertFailsWith`):

```kotlin
@Test
fun `spool deletes the temp file when the source throws an exception`() {
    val before = aktiveTempCount()
    val failing =
        object : ContentSource {
            override val filename = "x"
            override val contentType = "application/octet-stream"
            override fun open(): RawSource =
                object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw RuntimeException("boom")
                    override fun close() {}
                }
        }
    assertFailsWith<RuntimeException> { spool(failing, Md5Checksum()) }
    assertEquals(before, aktiveTempCount())
}

private fun aktiveTempCount(): Int =
    SystemFileSystem.list(SystemTemporaryDirectory).count { it.name.startsWith("aktive-") && it.name.endsWith(".tmp") }
```

- [ ] **Step 2: Run it — it should PASS already** (the change is behaviour-preserving for `Exception`; this test guards that the narrowing in Step 3 does not break the Exception cleanup path).

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.SpoolTest"`
Expected: PASS.

- [ ] **Step 3: Narrow both catches**

`Spool.kt:51` — change `} catch (e: Throwable) {` to `} catch (e: Exception) {`.
`FilesystemStorageService.kt:35` — change `} catch (e: Throwable) {` to `} catch (e: Exception) {`.

- [ ] **Step 4: Run module tests**

Run: `./gradlew :core:test :storage-fs:test`
Expected: PASS (SpoolTest still green; FS tests green).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add core storage-fs
git commit -m "fix: catch Exception instead of Throwable in spool/fs cleanup paths"
```

### Task 3.2: S3 `get` — narrow catch AND move temp-source construction inside the cleanup try

**Files:**
- Modify: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt:43-60`

This fixes the leak where `DeletingFileSource(tempFile)` (its constructor opens the file) runs *after* the cleanup `catch`. The failure mode (temp open failing post-download) is not reproducible without fault injection, so verification is by inspection + the existing `S3StorageServiceIT` round-trip staying green.

- [ ] **Step 1: Rewrite `get` so the return is inside the try**

Replace the body of `get` with:

```kotlin
override suspend fun get(key: String): RawSource {
    val tempFile = Path(SystemTemporaryDirectory, "aktive-s3-${UUID.randomUUID()}.tmp")
    try {
        client.getObject(
            GetObjectRequest {
                this.bucket = this@S3StorageService.bucket
                this.key = key
            },
        ) { resp ->
            val body = resp.body ?: error("S3 returned no body for key=$key")
            body.writeToFile(File(tempFile.toString()))
        }
        return DeletingFileSource(tempFile)
    } catch (e: Exception) {
        SystemFileSystem.delete(tempFile, mustExist = false)
        throw e
    }
}
```

- [ ] **Step 2: Verify it compiles and unit/compile is clean**

Run: `./gradlew :storage-s3:compileKotlin :storage-s3:test`
Expected: PASS (no unit tests touch this path; compilation must succeed).

- [ ] **Step 3 (optional, needs Docker): run the S3 IT round-trip**

Run: `./gradlew :storage-s3:integrationTest`
Expected: PASS (`put get exists delete round-trip` still green).

- [ ] **Step 4: Commit**

```bash
./gradlew spotlessApply
git add storage-s3
git commit -m "fix: construct DeletingFileSource inside the S3 get cleanup try (no temp leak)"
```

### Task 3.3: Scrimage CPU work on `Dispatchers.Default`

**Files:**
- Modify: `variant-scrimage/src/main/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessor.kt:26`

- [ ] **Step 1: Change the dispatcher**

In `process`, change `withContext(Dispatchers.IO)` to `withContext(Dispatchers.Default)`. (The input is an in-memory `ContentSource`, so there is no blocking I/O; image decode/encode is CPU-bound.)

- [ ] **Step 2: Run tests (behaviour unchanged)**

Run: `./gradlew :variant-scrimage:test`
Expected: PASS (all existing transform tests green).

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add variant-scrimage
git commit -m "perf: run Scrimage image processing on Dispatchers.Default (CPU-bound)"
```

### Task 3.4: attach compensation must be cancellation-safe

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt:45-50`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/AttachRollbackTest.kt`

- [ ] **Step 1: Write a failing test — rollback must run even when put fails on a cancelled scope**

Add to `AttachRollbackTest` (imports: `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.NonCancellable` not needed in test, `kotlin.coroutines.coroutineContext`, `kotlinx.coroutines.isActive`). Add a metadata fake whose `deleteBlob` refuses to run on a cancelled context, and a put that throws `CancellationException`:

```kotlin
@Test
fun `attach rollback runs even when put fails via CancellationException`() =
    runTest {
        val backing = InMemoryMetadataStore()
        var rolledBack = false
        val metadata =
            object : MetadataStore by backing {
                override suspend fun deleteBlob(id: BlobId) {
                    // 本物の suspend アダプタはキャンセル済みコンテキストでは実行されない。
                    // NonCancellable で包めばここに到達して実行できる。
                    check(coroutineContext.isActive) { "compensation ran on a cancelled context" }
                    backing.deleteBlob(id)
                    rolledBack = true
                }
            }
        val service =
            object : StorageService {
                override val name = "cancelling"
                override suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata): Unit =
                    throw CancellationException("upload cancelled")
                override suspend fun get(key: String): RawSource = throw UnsupportedOperationException()
                override suspend fun exists(key: String): Boolean = false
                override suspend fun delete(key: String) = Unit
                override suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl? = null
            }
        val sut = AktiveStorage(service, metadata, HmacReferenceSigner(ByteArray(32) { 1 }))

        assertFailsWith<CancellationException> {
            sut.attach(RecordRef("User", "1"), "avatar", ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()))
        }
        assertTrue(rolledBack)
        assertTrue(backing.blobs.isEmpty())
    }
```

> Note: this test builds the signer with a 32-byte key already (PR③ branches before PR② merges, so add `ByteArray(32) { 1 }` here directly; PR② will convert the rest). If running PR③ strictly before PR②’s `require`, `ByteArray(32){1}` still works because the `require` does not exist yet — it is forward-compatible.

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.AttachRollbackTest"`
Expected: FAIL — without the fix, `deleteBlob` is invoked directly; under a real cancellation the `check(isActive)` (or the suspend adapter) would abort. (In `runTest` the manual `CancellationException` does not cancel the scope, so the discriminating assertion is `rolledBack` running inside `NonCancellable`; if this test passes pre-fix in your runner, keep it as a guard and proceed — the structural fix is still required for real adapters.)

- [ ] **Step 3: Wrap the compensation in `NonCancellable`**

In `AktiveStorage.kt`, change the attach put-failure catch:

```kotlin
} catch (e: Exception) {
    withContext(NonCancellable) { metadata.deleteBlob(blob.id) }
    throw e
}
```

Add import `kotlinx.coroutines.NonCancellable` and ensure `kotlinx.coroutines.withContext` is imported.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.AttachRollbackTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add core
git commit -m "fix: run attach put-failure rollback under NonCancellable"
```

### Task 3.5: `filename` → `text` and length validation for other columns

**Files:**
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/Tables.kt:9`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`

- [ ] **Step 1: Write failing IT — long filename succeeds; over-length key throws clearly**

Add to `ExposedMetadataStoreIT` (use the test's existing blob-building helper; adjust names to match the file’s conventions):

```kotlin
@Test
fun `insertBlob accepts a very long filename`() =
    runBlocking {
        val blob = sampleBlob(filename = "a".repeat(5_000))
        store.insertBlob(blob)
        assertEquals("a".repeat(5_000), store.findBlob(blob.id)!!.filename)
    }

@Test
fun `insertBlob rejects an over-length key with a clear error`() {
    val blob = sampleBlob(key = "k".repeat(513))
    val ex = assertFailsWith<IllegalArgumentException> { runBlocking { store.insertBlob(blob) } }
    assertTrue(ex.message!!.contains("key"))
}
```

(If `sampleBlob(...)` does not exist, add a small helper mirroring the existing blob construction with overridable `key`/`filename`.)

- [ ] **Step 2: Run — expect FAIL** (Docker required)

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: FAIL — long filename violates `varchar(1024)`; over-length key throws a raw `ExposedSQLException`, not `IllegalArgumentException`.

- [ ] **Step 3: Change `filename` to `text` and add validation**

`Tables.kt:9` — change `val filename = varchar("filename", 1024)` to `val filename = text("filename")`.

`ExposedMetadataStore.kt` — add a private validator and call it at the top of `insertBlob` and the blob branch of `insertVariant`:

```kotlin
private fun validateColumns(blob: Blob) {
    require(blob.key.length <= 512) { "storage key exceeds 512 chars: ${blob.key.length}" }
    require(blob.checksum.length <= 128) { "checksum exceeds 128 chars: ${blob.checksum.length}" }
    require(blob.serviceName.length <= 64) { "serviceName exceeds 64 chars: ${blob.serviceName.length}" }
    require(blob.id.value.length <= 64) { "blob id exceeds 64 chars: ${blob.id.value.length}" }
}
```

Call `validateColumns(blob)` as the first line of `insertBlob(blob)` (before `dbQuery`) and `validateColumns(variant)` as the first line of `insertVariant(...)`.

- [ ] **Step 4: Run — expect PASS** (Docker required)

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add metadata-exposed-jdbc
git commit -m "fix: store filename as TEXT and validate other column lengths"
```

- [ ] **Step 6: Open PR③, merge to main.** Then branch PR② from updated `main`.

---

# Phase / PR② — クロスサービス＋セキュリティ

Branch: `fix/cross-service-security` off updated `main`. No ABI impact.

### Task 2.1: `resolveForDelivery` ownership guard + centralised `owns()`

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/DeliveryTest.kt`

- [ ] **Step 1: Failing test — a blob owned by another service is not deliverable here**

Add to `DeliveryTest` (signer key already 32 bytes if you do Task 2.2 first; otherwise use `ByteArray(32){1}`):

```kotlin
@Test
fun `resolveForDelivery returns null for a blob owned by another service`() =
    runTest {
        val service = InMemoryStorageService(name = "s3", presignSupported = true)
        val metadata = InMemoryMetadataStore()
        val sut = storage(service, metadata) // helper in this file
        val att = sut.attach(RecordRef("User", "1"), "avatar", ContentSource.ofBytes("a.png", "image/png", "x".encodeToByteArray()))
        val blob = sut.blobOf(att)!!
        // shared metadata, but the blob is marked as owned by a different service
        metadata.blobs[blob.id.value] = blob.copy(serviceName = "other")
        val token = sut.signedReference(blob, 5.minutes)

        assertNull(sut.resolveForDelivery(token))
    }
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.DeliveryTest"`
Expected: FAIL — currently `resolveForDelivery` signs/fetches regardless of `serviceName`.

- [ ] **Step 3: Add `owns()` and guard `resolveForDelivery`; route the other three sites through it**

In `AktiveStorage.kt`:

```kotlin
private fun owns(blob: Blob): Boolean = blob.serviceName == service.name
```

In `resolveForDelivery`, after `val blob = metadata.findBlob(blobId) ?: return null`, add:

```kotlin
if (!owns(blob)) return null
```

Replace the inline ownership checks to use `owns(...)`:
- `variant()`: `check(owns(blob)) { "variant() must run on the owning service: blob=${blob.serviceName}, current=${service.name}" }`
- `detach()`: `if (!owns(blob)) return`
- `reclaimUnattached()`: `if (!owns(blob)) continue`

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :core:test`
Expected: PASS (new test green; existing delivery/detach/reclaim/variant tests unchanged).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add core
git commit -m "fix: guard resolveForDelivery by owning service; centralise owns()"
```

### Task 2.2: HMAC minimum key length (32 bytes) + update all test keys

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/HmacReferenceSigner.kt:11-15`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/HmacReferenceSignerTest.kt`
- Modify (test keys): the 13 sites listed below.

- [ ] **Step 1: Failing test — short key is rejected**

Add to `HmacReferenceSignerTest`:

```kotlin
@Test
fun `constructor rejects a key shorter than 32 bytes`() {
    assertFailsWith<IllegalArgumentException> { HmacReferenceSigner("short".encodeToByteArray()) }
}
```

Also change the existing `key`/`"other"` in this file to 32-byte keys so the other tests keep passing:
- line 11: `private val key = ByteArray(32) { 1 }`
- line 42: `HmacReferenceSigner(ByteArray(32) { 2 }, fixedClock)`

- [ ] **Step 2: Run — expect FAIL** on the new test, and the existing tests still rely on 32-byte keys now.

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.HmacReferenceSignerTest"`
Expected: FAIL (no `require` yet, so the short-key test fails).

- [ ] **Step 3: Add the `require`**

In `HmacReferenceSigner` constructor body, add as the first line:

```kotlin
init {
    require(secretKey.size >= 32) { "HMAC secret key must be >= 32 bytes, was ${secretKey.size}" }
}
```

(Place `init` before `keySpec` initialisation, or guard inside; ensure it runs before `SecretKeySpec`.)

- [ ] **Step 4: Update every remaining signer construction to a 32-byte key**

Replace `HmacReferenceSigner("k".encodeToByteArray())` with `HmacReferenceSigner(ByteArray(32) { 1 })` in:
`AttachRollbackTest.kt:43` (if not already done in 3.4), `VariantTest.kt:24` & `:138`, `DetachRefCountTest.kt:20`, `DeliveryTest.kt:21`, `AttachmentsAndDetachTest.kt:19`, `PurgeRecordTest.kt:17`, `BlobOfTest.kt:14`, `AttachTest.kt:17`, `VariantCascadeTest.kt:25`, `ReclaimUnattachedTest.kt:18`.
And `EndToEndIT.kt:61`: `HmacReferenceSigner(ByteArray(32) { 7 })`.

- [ ] **Step 5: Run — expect PASS**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add core integration-tests
git commit -m "fix: require >=32-byte HMAC secret key; update test keys"
```

### Task 2.3: FS path hardening (reject control chars + realpath containment)

**Files:**
- Modify: `storage-fs/src/main/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageService.kt:53-59`
- Test: `storage-fs/src/test/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageServiceTest.kt`

- [ ] **Step 1: Failing tests — NUL/control char rejected; resolved path stays under root**

Add to `FilesystemStorageServiceTest`:

```kotlin
@Test
fun `rejects keys with control characters`() =
    runTest {
        assertFailsWith<IllegalArgumentException> {
            s.put("a b", ContentSource.ofBytes("x", "text/plain", "x".encodeToByteArray()), meta)
        }
    }
```

(Reuse the test's existing `s` service, `meta`, and `runTest` setup; if `meta` isn't defined, build `ObjectMetadata("text/plain", 1, "")`.)

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :storage-fs:test --tests "net.brightroom.aktivestorage.storage.fs.FilesystemStorageServiceTest"`
Expected: FAIL — control chars currently pass `resolveSafe`.

- [ ] **Step 3: Harden `resolveSafe`**

Replace `resolveSafe` with:

```kotlin
private fun resolveSafe(key: String): Path {
    val parts = key.split('/', '\\')
    require(
        key.isNotBlank() &&
            key.none { it.isISOControl() } &&
            parts.none { it == ".." || it == "." || it.isEmpty() },
    ) { "invalid storage key: $key" }
    val resolved = Path(root.toString(), *parts.toTypedArray())
    val rootCanonical = java.io.File(root.toString()).canonicalFile
    val targetCanonical = java.io.File(resolved.toString()).canonicalFile
    require(
        targetCanonical == rootCanonical ||
            targetCanonical.toPath().startsWith(rootCanonical.toPath()),
    ) { "storage key escapes root: $key" }
    return resolved
}
```

Add imports `java.io.File` (already used elsewhere? add if missing) and rely on `kotlin.io.path` interop via `java.io.File.toPath()`.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :storage-fs:test`
Expected: PASS (control-char test green; existing put/get/exists/delete and traversal-rejection tests green).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add storage-fs
git commit -m "fix: reject control-char keys and enforce realpath containment in FS adapter"
```

- [ ] **Step 6: Open PR②, merge.** Branch PR① from updated `main`.

---

# Phase / PR① — variant 正しさ

Branch: `fix/variant-correctness` off updated `main`. **ABI impact → regenerate baselines at the end.**

### Task 1.1: Reject non-right-angle `Rotate` at construction

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Variant.kt:51-53`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/VariationTest.kt`

- [ ] **Step 1: Failing test**

Add to `VariationTest`:

```kotlin
@Test
fun `Rotate rejects non-right-angle degrees`() {
    assertFailsWith<IllegalArgumentException> { Transform.Rotate(45) }
}

@Test
fun `Rotate accepts right angles and zero`() {
    listOf(0, 90, 180, 270, -90, 360).forEach { Transform.Rotate(it) }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.VariationTest"`
Expected: FAIL — `Rotate(45)` constructs without error today.

- [ ] **Step 3: Add validation**

In `Variant.kt`, give `Rotate` an `init`:

```kotlin
public data class Rotate(
    val degrees: Int,
) : Transform {
    init {
        require(degrees % 90 == 0) { "Rotate degrees must be a multiple of 90, was $degrees" }
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.VariationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add core
git commit -m "fix: reject non-right-angle Rotate at construction"
```

### Task 1.2: Single-axis Resize derives the missing axis from aspect ratio

**Files:**
- Modify: `variant-scrimage/src/main/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessor.kt:66-74`
- Test: `variant-scrimage/src/test/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessorTest.kt`

- [ ] **Step 1: Failing test — width-only FIT upscales preserving aspect**

`samplePng()` is 60×40. Add:

```kotlin
@Test
fun `resize with only width derives height from aspect ratio`() =
    runTest {
        val out = processor.process(source(samplePng()), Variation.of(Transform.Resize(120, null, ResizeMode.FIT)))
        val img = decode(out)
        assertEquals(120, img.width)
        assertEquals(80, img.height) // 60x40 -> 120x80
    }
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :variant-scrimage:test --tests "net.brightroom.aktivestorage.variant.scrimage.ScrimageVariantProcessorTest"`
Expected: FAIL — current code substitutes the source height (40), so `max(120,40)` keeps the image at 60×40.

- [ ] **Step 3: Derive the missing axis before applying mode**

Replace `applyResize` with:

```kotlin
private fun ImmutableImage.applyResize(resize: Transform.Resize): ImmutableImage {
    val w = resize.width ?: ((resize.height!! * width.toDouble() / height).roundToInt()).coerceAtLeast(1)
    val h = resize.height ?: ((resize.width!! * height.toDouble() / width).roundToInt()).coerceAtLeast(1)
    return when (resize.mode) {
        ResizeMode.FIT -> max(w, h)
        ResizeMode.LIMIT -> bound(w, h)
        ResizeMode.FILL -> cover(w, h)
    }
}
```

Add import `kotlin.math.roundToInt`. (When both axes are given, `w`/`h` are exactly the requested values — unchanged behaviour. When one is null, it is computed from the source aspect ratio; the chosen `mode` then governs enlarge policy, e.g. `LIMIT` still won't upscale.)

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :variant-scrimage:test`
Expected: PASS (new test green; existing FIT-both-axes / crop / convert / rotate / grayscale tests green).

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add variant-scrimage
git commit -m "fix: derive missing Resize axis from source aspect ratio"
```

### Task 1.3: Add `DuplicateVariantException` + `VariantSourceTooLargeException`

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/VariantExceptions.kt`

- [ ] **Step 1: Create the exceptions**

```kotlin
package net.brightroom.aktivestorage

/** insertVariant が (originBlobId, variationDigest) の一意制約に衝突したときに投げる。並行初回生成の収束に使う。 */
public class DuplicateVariantException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** variant 元画像が maxVariantSourceBytes を超える場合に投げる。 */
public class VariantSourceTooLargeException(
    public val byteSize: Long,
    public val maxBytes: Long,
) : RuntimeException("variant source $byteSize bytes exceeds limit $maxBytes")
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add core
git commit -m "feat: add DuplicateVariantException and VariantSourceTooLargeException"
```

### Task 1.4: `MetadataStore.isVariantBlob` (+ fake + Exposed impl)

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/.../ExposedMetadataStoreIT.kt`

- [ ] **Step 1: Add to the port** (in `Ports.kt`, inside `MetadataStore`):

```kotlin
/** blobId が派生（いずれかの variant 記録の variantBlobId）なら true。variant-of-variant 禁止の判定に使う。 */
public suspend fun isVariantBlob(blobId: BlobId): Boolean
```

Also update the `insertVariant` KDoc to state: "一意制約違反時は [DuplicateVariantException] を投げること。"

- [ ] **Step 2: Implement in the fake** (`InMemoryMetadataStore`):

```kotlin
override suspend fun isVariantBlob(blobId: BlobId): Boolean = blobId.value in variants.values
```

- [ ] **Step 3: Implement in Exposed** (`ExposedMetadataStore`):

```kotlin
override suspend fun isVariantBlob(blobId: BlobId): Boolean =
    dbQuery {
        VariantRecordsTable
            .selectAll()
            .where { VariantRecordsTable.variantBlobId eq blobId.value }
            .limit(1)
            .any()
    }
```

- [ ] **Step 4: Failing IT for the Exposed impl**

Add to `ExposedMetadataStoreIT`:

```kotlin
@Test
fun `isVariantBlob distinguishes origins from variants`() =
    runBlocking {
        val origin = sampleBlob()
        val variant = sampleBlob()
        store.insertBlob(origin)
        store.insertVariant(origin.id, "digest1", variant)
        assertTrue(store.isVariantBlob(variant.id))
        assertFalse(store.isVariantBlob(origin.id))
    }
```

- [ ] **Step 5: Run**

Run: `./gradlew :core:test :metadata-exposed-jdbc:integrationTest`
Expected: PASS (core compiles with the new port method implemented in the fake; Exposed IT green).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add core metadata-exposed-jdbc
git commit -m "feat: add MetadataStore.isVariantBlob"
```

### Task 1.5: Exposed `insertVariant` throws `DuplicateVariantException` on unique-constraint collision

**Files:**
- Modify: `metadata-exposed-jdbc/.../ExposedMetadataStore.kt:146-168`
- Test: `metadata-exposed-jdbc/.../ExposedMetadataStoreIT.kt`

- [ ] **Step 1: Failing IT — second insert of the same (origin, digest) throws DuplicateVariantException**

```kotlin
@Test
fun `insertVariant throws DuplicateVariantException on duplicate (origin, digest)`() =
    runBlocking {
        val origin = sampleBlob()
        store.insertBlob(origin)
        store.insertVariant(origin.id, "d", sampleBlob())
        assertFailsWith<DuplicateVariantException> {
            store.insertVariant(origin.id, "d", sampleBlob())
        }
    }
```

- [ ] **Step 2: Run — expect FAIL** (currently throws raw `ExposedSQLException`).

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: FAIL.

- [ ] **Step 3: Translate the constraint violation**

Wrap the inserts in `insertVariant`’s `dbQuery` body, catching the unique/PK violation (SQLState class 23) and rethrowing:

```kotlin
override suspend fun insertVariant(
    originBlobId: BlobId,
    variationDigest: String,
    variant: Blob,
): Unit {
    validateColumns(variant)
    try {
        dbQuery {
            BlobsTable.insert { /* unchanged */ }
            VariantRecordsTable.insert { /* unchanged */ }
            Unit
        }
    } catch (e: ExposedSQLException) {
        if (e.sqlState?.startsWith("23") == true) {
            throw DuplicateVariantException("variant ($originBlobId, $variationDigest) already exists", e)
        }
        throw e
    }
}
```

Add import `org.jetbrains.exposed.v1.exceptions.ExposedSQLException`. (`sqlState` "23xxx" = integrity-constraint violation across Postgres/H2.)

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add metadata-exposed-jdbc
git commit -m "fix: translate unique-constraint violation to DuplicateVariantException"
```

### Task 1.6: `AktiveStorage.variant` — nesting guard, size guard, typed duplicate handling, cancellation-safe compensation, `newBlob` factory

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/VariantTest.kt`

- [ ] **Step 1: Update the existing race test to the typed exception**

In `VariantTest.kt`, the `concurrent insert conflict` fake (line 121-129): change `throw IllegalStateException("duplicate key")` to `throw DuplicateVariantException("duplicate key")`.

- [ ] **Step 2: Add failing tests — nesting rejected, size limit enforced, non-duplicate failure compensates**

```kotlin
@Test
fun `variant of a variant is rejected`() =
    runTest {
        val service = InMemoryStorageService()
        val metadata = InMemoryMetadataStore()
        val sut = storage(service, metadata)
        val origin = attachImage(sut)
        val v = sut.variant(origin, Variation.of(Transform.Grayscale))
        assertFailsWith<IllegalArgumentException> { sut.variant(v, Variation.of(Transform.Grayscale)) }
    }

@Test
fun `variant rejects an origin larger than the limit before downloading`() =
    runTest {
        val service = InMemoryStorageService()
        val metadata = InMemoryMetadataStore()
        val sut =
            AktiveStorage(
                service = service,
                metadata = metadata,
                signer = HmacReferenceSigner(ByteArray(32) { 1 }),
                variantProcessor = FakeVariantProcessor(),
                maxVariantSourceBytes = 2,
            )
        val origin = attachImage(sut) // "PNG" = 3 bytes > 2
        assertFailsWith<VariantSourceTooLargeException> { sut.variant(origin, Variation.of(Transform.Grayscale)) }
    }

@Test
fun `variant deletes the stored object when insertVariant fails for a non-duplicate reason`() =
    runTest {
        val service = InMemoryStorageService()
        val backing = InMemoryMetadataStore()
        val metadata =
            object : MetadataStore by backing {
                override suspend fun insertVariant(originBlobId: BlobId, variationDigest: String, variant: Blob): Unit =
                    throw IllegalStateException("db down")
            }
        val sut =
            AktiveStorage(service, metadata, HmacReferenceSigner(ByteArray(32) { 1 }), variantProcessor = FakeVariantProcessor())
        val origin = attachImage(sut)
        assertFailsWith<IllegalStateException> { sut.variant(origin, Variation.of(Transform.Grayscale)) }
        // the variant object must not be left behind
        assertTrue(service.objects.keys.none { it.startsWith("${origin.key}/variants/") })
    }
```

- [ ] **Step 3: Run — expect FAIL**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.VariantTest"`
Expected: FAIL on the three new tests.

- [ ] **Step 4: Implement in `AktiveStorage`**

Add the constructor parameter (end of the list, with default):

```kotlin
private val maxVariantSourceBytes: Long = 50L * 1024 * 1024,
```

Extract the blob factory and use it in `attach` and `variant`:

```kotlin
@OptIn(ExperimentalUuidApi::class)
private fun newBlob(key: String, filename: String, spooled: SpooledContent): Blob =
    Blob(
        id = BlobId(Uuid.random().toString()),
        key = key,
        filename = filename,
        contentType = spooled.contentType,
        byteSize = spooled.byteSize,
        checksum = spooled.checksumBase64,
        serviceName = service.name,
        createdAt = clock.now(),
    )
```

In `attach`, replace the inline `Blob(...)` with `val blob = newBlob(key, spooled.filename, spooled)`.

Rewrite `variant` to add the guards and typed handling:

```kotlin
public suspend fun variant(blob: Blob, variation: Variation): Blob {
    val processor = variantProcessor ?: error("variant() requires a VariantProcessor; none was injected")
    check(owns(blob)) { "variant() must run on the owning service: blob=${blob.serviceName}, current=${service.name}" }
    require(!metadata.isVariantBlob(blob.id)) { "variant() origin must not itself be a variant: ${blob.id.value}" }
    if (blob.byteSize > maxVariantSourceBytes) throw VariantSourceTooLargeException(blob.byteSize, maxVariantSourceBytes)

    val digest = digestOf(variation)
    metadata.findVariant(blob.id, digest)?.let { return it }

    val originBytes = service.get(blob.key).buffered().use { it.readByteArray() }
    val origin = ContentSource.ofBytes(blob.filename, blob.contentType, originBytes)
    val processed = processor.process(origin, variation)

    val spooled = spool(processed, checksum)
    try {
        val key = "${blob.key}/variants/$digest"
        val variantBlob = newBlob(key, spooled.filename, spooled)
        service.put(key, spooled, ObjectMetadata(variantBlob.contentType, variantBlob.byteSize, variantBlob.checksum))
        return try {
            metadata.insertVariant(blob.id, digest, variantBlob)
            variantBlob
        } catch (e: DuplicateVariantException) {
            // 並行初回生成の収束: 既存の派生を返す。
            metadata.findVariant(blob.id, digest) ?: throw e
        } catch (e: Exception) {
            // 真の失敗: put 済み実体を補償削除してから再throw（キャンセルでも実行）。
            withContext(NonCancellable) { service.delete(key) }
            throw e
        }
    } finally {
        spooled.cleanup()
    }
}
```

Add imports `kotlinx.coroutines.NonCancellable`, `kotlinx.coroutines.withContext`.

> Note: the `require(!isVariantBlob)` adds one metadata read per `variant()` call (before the cheap cached-variant lookup); acceptable. The size check uses the persisted `blob.byteSize`, so oversized origins are rejected before any download.

- [ ] **Step 5: Run — expect PASS**

Run: `./gradlew :core:test`
Expected: PASS (new tests green; existing variant/reuse/race/owning-service tests green; `attach` tests green via `newBlob`).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add core
git commit -m "fix: variant nesting/size guards, typed duplicate handling, NonCancellable compensation; extract newBlob"
```

### Task 1.7: Update redundant-copy note + regenerate ABI baselines

**Files:**
- Modify: `core/api/core.api`, `metadata-exposed-jdbc/api/metadata-exposed-jdbc.api`

- [ ] **Step 1: Check ABI drift**

Run: `./gradlew apiCheck`
Expected: FAIL — new public symbols (`isVariantBlob`, exceptions, `AktiveStorage` constructor arg).

- [ ] **Step 2: Regenerate baselines**

Run: `./gradlew apiDump`

- [ ] **Step 3: Verify**

Run: `./gradlew apiCheck`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add core/api metadata-exposed-jdbc/api
git commit -m "build: regenerate ABI baselines for variant correctness changes"
```

- [ ] **Step 5: Open PR①, merge.** Branch PR④ from updated `main`.

---

# Phase / PR④ — 品質リファクタ

Branch: `refactor/quality` off updated `main`. **ABI impact (method removal) → regenerate baselines; next release is minor-or-higher.**

### Task 4.1: Remove `StorageService.exists()`

**Files:**
- Modify: `core/.../Ports.kt:20-21` (remove the declaration)
- Modify: `storage-s3/.../S3StorageService.kt:62-71` (remove override)
- Modify: `storage-fs/.../FilesystemStorageService.kt:43` (remove override)
- Modify: `core/.../fakes/InMemoryStorageService.kt:30` (remove override)
- Modify: `core/.../AttachRollbackTest.kt:25` (remove override in `FailingStorageService`)
- Modify: tests asserting `s.exists(...)`: `DetachRefCountTest.kt`, `AttachmentsAndDetachTest.kt`, `PurgeRecordTest.kt`, `ReclaimUnattachedTest.kt`, `storage-fs/.../FilesystemStorageServiceTest.kt`, `storage-s3/.../S3StorageServiceIT.kt`.

- [ ] **Step 1: Rewrite the test assertions to not use `exists()`**

Replace `assertTrue(s.exists(key))` / `assertFalse(s.exists(key))` with object-presence checks via the fake’s `objects` map (core tests use `InMemoryStorageService`):
- core tests using `InMemoryStorageService s`: `assertTrue(key in s.objects)` / `assertFalse(key in s.objects)`.
- `FilesystemStorageServiceTest`: replace `s.exists("a/b/key1")` with a `get`-based check — `assertContentEquals(bytes, s.get("a/b/key1").buffered().readByteArray())` for the present case, and `assertFailsWith<Exception> { s.get("k") }` (or check the file path) for the absent case.
- `S3StorageServiceIT` `put get exists delete round-trip`: drop the two `exists` assertions; assert the object is gone after delete via `assertFailsWith { s.get("k1") }`.

- [ ] **Step 2: Remove the port method and all overrides**

Delete `public suspend fun exists(key: String): Boolean` from `StorageService` in `Ports.kt`, and the four `override suspend fun exists(...)` implementations listed above.

- [ ] **Step 3: Run all unit tests**

Run: `./gradlew :core:test :storage-fs:test`
Expected: PASS.

- [ ] **Step 4 (Docker): run ITs that referenced exists**

Run: `./gradlew :storage-s3:integrationTest`
Expected: PASS.

- [ ] **Step 5: Regenerate ABI baselines**

Run: `./gradlew apiDump && ./gradlew apiCheck`
Expected: `apiCheck` PASS after dump (core/storage-s3/storage-fs baselines lose `exists`).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add core storage-s3 storage-fs
git commit -m "refactor!: remove unused StorageService.exists() from the port"
```

### Task 4.2: (already done) `newBlob` factory

The Blob-construction duplication was removed in Task 1.6 (`newBlob`). No separate work; verify `attach` and `variant` both use `newBlob` and no inline `Blob(...)` remains in `AktiveStorage.kt` except inside `newBlob`.

- [ ] **Step 1: Grep check**

Run: `grep -n "Blob(" core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
Expected: only the construction inside `newBlob`.

- [ ] **Step 2: Open PR④, merge.**

---

## Self-Review

**Spec coverage:**
- #1 Rotate → Task 1.1. #2 single-axis resize → Task 1.2. #3 resolveForDelivery guard → Task 2.1. #4 HMAC key → Task 2.2. #5 variant orphan → Task 1.6 (compensation) + 1.5 (typed). #6 nested variant → Task 1.4 + 1.6. #7 cancellation (attach) → Task 3.4; (variant) → Task 1.6. #8 S3 temp leak → Task 3.2; FS path → Task 2.3. #9 variant memory → Task 1.6 (size guard) + redundant-copy note. #10 dispatcher → Task 3.3. #11 varchar → Task 3.5. #13 exists() → Task 4.1. #14 Blob factory → Task 1.6/4.2. Throwable→Exception → Task 3.1 (+3.2 for S3). All covered.
- Design-accepted tradeoffs (§8 of spec) intentionally untouched.

**Placeholder scan:** No TBD/TODO; each code step shows real code. The two structurally-hard-to-unit-test fixes (3.2 S3 temp open failure, 3.4 real cancellation) are flagged with explicit inspection-based verification rather than fake assertions.

**Type consistency:** `isVariantBlob(BlobId): Boolean`, `DuplicateVariantException`, `VariantSourceTooLargeException`, `newBlob(key, filename, spooled)`, `owns(blob)`, `maxVariantSourceBytes` used consistently across tasks. `insertVariant` duplicate contract (Task 1.5 Exposed throws → Task 1.6 core catches `DuplicateVariantException`) lines up.

**Known caveats for the executor:**
- `sampleBlob(...)` helper in `ExposedMetadataStoreIT` may need adding (mirror the file’s existing blob construction with overridable `key`/`filename`).
- The exact Exposed `ExposedSQLException` package (`org.jetbrains.exposed.v1.exceptions`) and `sqlState` accessor should be confirmed on first compile (Task 1.5 Step 2 fail-run surfaces it).
- Integration tasks need Docker (Testcontainers Postgres/MinIO).

# End-to-End Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate full-heap materialization on the upload spool, S3 `put`, and S3 `get` paths so large blobs stream end-to-end.

**Architecture:** Behavior-preserving streaming refactor. `spool()` copies the upload to its temp file in fixed chunks while updating the MD5 incrementally. S3 `put` sends a replayable `ByteStream.SourceStream` that re-opens the (re-openable) `ContentSource` on each `readFrom()`. S3 `get` streams the response body to a temp file inside the SDK callback and returns a `RawSource` over it that deletes the file on `close()`. Public API signatures are unchanged, so no BCV baseline update.

**Tech Stack:** Kotlin, kotlinx-io 0.9.0, AWS SDK for Kotlin (s3 1.6.91) / smithy-kotlin runtime 1.6.14, JUnit 6, Testcontainers (MinIO/Postgres), Gradle.

**Note on TDD framing:** This is a behavior-preserving refactor — the observable contract (byteSize, MD5 checksum, returned bytes) is identical before and after. The new tests are characterization/regression guards on large inputs, not red-first tests. Each task: add/extend the test, keep it (and the full suite) green, refactor the implementation, re-run. Spotless (ktlint) gates commits, so run `./gradlew spotlessApply` before each commit.

**Verified API facts (smithy-kotlin 1.6.14 / kotlinx-io 0.9.0):**
- `kotlinx.io.Source.readAtMostTo(sink: ByteArray, startIndex = 0, endIndex = sink.size): Int` — returns `-1` at EOF.
- `kotlinx.io.Sink.write(source: ByteArray, startIndex = 0, endIndex = source.size)`.
- `kotlinx.io.Source.asInputStream(): InputStream` (import `kotlinx.io.asInputStream`; requires a buffered `Source`).
- `aws.smithy.kotlin.runtime.content.ByteStream.SourceStream` — abstract `readFrom(): SdkSource`, open `contentLength: Long?`, open `isOneShot: Boolean`. When `isOneShot = false`, `readFrom()` MUST return a fresh source each call.
- `aws.smithy.kotlin.runtime.io.source` — `InputStream.source(): SdkSource`.
- `aws.smithy.kotlin.runtime.content.writeToFile` — `suspend ByteStream.writeToFile(file: java.io.File): Long` (runs on `Dispatchers.IO`).

---

## File Structure

- **Modify** `core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt` — chunked streaming copy + incremental MD5.
- **Create** `core/src/test/kotlin/net/brightroom/aktivestorage/SpoolTest.kt` — large-input characterization of size/checksum/round-trip.
- **Create** `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/ContentSourceByteStream.kt` — internal replayable `SourceStream` over a `ContentSource`.
- **Create** `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/DeletingFileSource.kt` — internal `RawSource` that deletes its backing temp file on close.
- **Modify** `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt` — stream `put` and `get`.
- **Modify** `storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt` — large streaming round-trip + temp-cleanup assertion.
- **Modify** `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt` — large attach → redirect round-trip.

---

## Task 1: Stream the upload spool (core)

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/SpoolTest.kt` (create)

- [ ] **Step 1: Write the characterization test**

Create `core/src/test/kotlin/net/brightroom/aktivestorage/SpoolTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpoolTest {
    @Test
    fun `spool computes size and base64 md5 and round-trips a multi-MB payload`() {
        val size = 5 * 1024 * 1024 // 5 MiB, far larger than the 8 KiB copy chunk
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val content = ContentSource.ofBytes("big.bin", "application/octet-stream", bytes)

        val spooled = spool(content)
        try {
            assertEquals(size.toLong(), spooled.byteSize)

            val expectedChecksum =
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
            assertEquals(expectedChecksum, spooled.checksumBase64)

            val readBack = spooled.open().buffered().use { it.readByteArray() }
            assertContentEquals(bytes, readBack)
        } finally {
            spooled.cleanup()
        }
    }
}
```

- [ ] **Step 2: Run the test against the current implementation (baseline green)**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.SpoolTest"`
Expected: PASS (the current heap-based `spool()` already satisfies this contract; this locks the contract before refactoring).

- [ ] **Step 3: Refactor `spool()` to stream in chunks**

In `core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt`, replace the body of `spool(...)` (the `val bytes = ... readByteArray()` / `digest.update(bytes)` / `sink.write(bytes)` block) with a chunked loop. The final function:

```kotlin
internal fun spool(content: ContentSource): SpooledContent {
    val tempFile = Path(SystemTemporaryDirectory, "aktive-${UUID.randomUUID()}.tmp")
    val digest = MessageDigest.getInstance("MD5")
    val chunk = ByteArray(8192)
    var byteSize = 0L
    content.open().buffered().use { src ->
        SystemFileSystem.sink(tempFile).buffered().use { sink ->
            while (true) {
                val n = src.readAtMostTo(chunk, 0, chunk.size)
                if (n == -1) break
                digest.update(chunk, 0, n)
                sink.write(chunk, 0, n)
                byteSize += n
            }
        }
    }
    val checksum = Base64.getEncoder().encodeToString(digest.digest())
    return SpooledContent(tempFile, byteSize, checksum, content.filename, content.contentType)
}
```

Then remove the now-unused import `import kotlinx.io.readByteArray` (the `RawSource` and `buffered` imports remain in use).

- [ ] **Step 4: Run the spool test and the full core suite**

Run: `./gradlew :core:test`
Expected: PASS (SpoolTest plus existing AttachTest/AttachRollbackTest/etc. stay green).

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/SpoolTest.kt
git commit -m "perf: stream the upload spool instead of buffering the full payload in heap"
```

---

## Task 2: Stream the S3 put body (storage-s3)

**Files:**
- Create: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/ContentSourceByteStream.kt`
- Modify: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt:28-41` (the `put` function and imports)
- Test: `storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt` (extend)

- [ ] **Step 1: Create the replayable ByteStream**

Create `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/ContentSourceByteStream.kt`:

```kotlin
package net.brightroom.aktivestorage.storage.s3

import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.io.SdkSource
import aws.smithy.kotlin.runtime.io.source
import kotlinx.io.asInputStream
import kotlinx.io.buffered
import net.brightroom.aktivestorage.ContentSource

/**
 * Streams a [ContentSource] to S3 without materializing it in heap.
 * Replayable ([isOneShot] = false): each [readFrom] re-opens the source, so the SDK may
 * re-read the body for signing or retries (the prior `fromBytes` body was replayable too).
 */
internal class ContentSourceByteStream(
    private val content: ContentSource,
    override val contentLength: Long?,
) : ByteStream.SourceStream() {
    override val isOneShot: Boolean = false

    override fun readFrom(): SdkSource = content.open().buffered().asInputStream().source()
}
```

- [ ] **Step 2: Use it in `put` and drop the heap read**

In `S3StorageService.kt`, replace the `put` body so it no longer calls `readByteArray()`:

```kotlin
    override suspend fun put(
        key: String,
        content: ContentSource,
        meta: ObjectMetadata,
    ) {
        client.putObject {
            this.bucket = this@S3StorageService.bucket
            this.key = key
            this.contentType = meta.contentType
            this.contentLength = meta.byteSize
            this.body = ContentSourceByteStream(content, meta.byteSize)
        }
    }
```

Remove the now-unused imports `import aws.smithy.kotlin.runtime.content.ByteStream` and `import aws.smithy.kotlin.runtime.content.toByteArray` only if they are no longer referenced after Task 3 — to avoid churn, defer import cleanup to Task 3 Step 4 where `get` is also rewritten. For now just add no new imports here (`ContentSourceByteStream` is same-package).

- [ ] **Step 3: Add a large streaming round-trip test**

In `S3StorageServiceIT.kt`, add this test (requires Docker for MinIO):

```kotlin
    @Test
    fun `put and get stream a large object without corruption`() =
        runBlocking {
            val s = service()
            val size = 16 * 1024 * 1024 // 16 MiB
            val payload = ByteArray(size) { (it % 251).toByte() }
            s.put(
                "big",
                ContentSource.ofBytes("big.bin", "application/octet-stream", payload),
                ObjectMetadata("application/octet-stream", size.toLong(), "chk"),
            )
            val readBack = s.get("big").buffered().use { it.readByteArray() }
            assertContentEquals(payload, readBack)
            s.delete("big")
        }
```

- [ ] **Step 4: Run the S3 integration tests**

Run: `./gradlew :storage-s3:integrationTest`
Expected: PASS — the existing round-trip/presign tests plus the new large streaming test. (Requires a running Docker daemon.)

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/ContentSourceByteStream.kt \
        storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt \
        storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt
git commit -m "perf: stream the S3 put body via a replayable ByteStream"
```

---

## Task 3: Stream the S3 get body to a self-deleting temp file (storage-s3)

**Files:**
- Create: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/DeletingFileSource.kt`
- Modify: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt:43-52` (the `get` function and imports)
- Test: `storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt` (extend)

- [ ] **Step 1: Create the self-deleting source**

Create `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/DeletingFileSource.kt`:

```kotlin
package net.brightroom.aktivestorage.storage.s3

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [RawSource] over a downloaded temp file that deletes the file once the consumer closes it.
 * Used by the S3 adapter so a streamed download does not outlive its backing file.
 */
internal class DeletingFileSource(
    private val path: Path,
) : RawSource {
    private val delegate: RawSource = SystemFileSystem.source(path)

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = delegate.readAtMostTo(sink, byteCount)

    override fun close() {
        try {
            delegate.close()
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}
```

- [ ] **Step 2: Rewrite `get` to spool the body to a temp file inside the SDK callback**

In `S3StorageService.kt`, replace the `get` function:

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
        } catch (e: Throwable) {
            SystemFileSystem.delete(tempFile, mustExist = false)
            throw e
        }
        return DeletingFileSource(tempFile)
    }
```

- [ ] **Step 3: Fix imports in `S3StorageService.kt`**

Set the import block so it matches what `put` (Task 2) and `get` now use, removing the heap-era imports. The file should import exactly:

```kotlin
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.writeToFile
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import java.io.File
import java.util.UUID
import kotlin.time.Duration
```

(Removed vs. the original: `aws.smithy.kotlin.runtime.content.ByteStream`, `aws.smithy.kotlin.runtime.content.toByteArray`, `kotlinx.io.Buffer`, `kotlinx.io.buffered`, `kotlinx.io.readByteArray`. Added: `writeToFile`, `Path`, `SystemFileSystem`, `SystemTemporaryDirectory`, `File`, `UUID`.)

- [ ] **Step 4: Add a temp-cleanup characterization test**

In `S3StorageServiceIT.kt`, add a test asserting the streamed download leaves no temp file behind after the consumer closes the source:

```kotlin
    @Test
    fun `get streams via a temp file that is deleted on close`() =
        runBlocking {
            val s = service()
            s.put("k3", ContentSource.ofBytes("f", "text/plain", "stream-me".encodeToByteArray()), meta("stream-me"))

            val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"))
            fun spoolFiles() = tmpDir.listFiles { _, n -> n.startsWith("aktive-s3-") }?.size ?: 0
            val before = spoolFiles()

            val source = s.get("k3")
            val bytes = source.buffered().use { it.readByteArray() }
            assertContentEquals("stream-me".encodeToByteArray(), bytes)

            assertEquals(before, spoolFiles())
            s.delete("k3")
        }
```

Add the import `import kotlin.test.assertEquals` to the test file if not already present.

- [ ] **Step 5: Run the S3 integration tests**

Run: `./gradlew :storage-s3:integrationTest`
Expected: PASS — round-trip, presign, large streaming, and temp-cleanup tests all green.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/DeletingFileSource.kt \
        storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt \
        storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt
git commit -m "perf: stream the S3 get body to a self-deleting temp file"
```

---

## Task 4: End-to-end large-payload guard (integration-tests)

**Files:**
- Modify: `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt`

- [ ] **Step 1: Add a large attach → redirect round-trip test**

In `EndToEndIT.kt`, add:

```kotlin
    @Test
    fun `attach and serve a large payload end to end`() =
        runBlocking {
            val size = 16 * 1024 * 1024 // 16 MiB through spool -> S3 put -> presigned GET
            val payload = ByteArray(size) { (it % 251).toByte() }
            val record = RecordRef("User", "big")

            val att = storage.attach(record, "dump", ContentSource.ofBytes("dump.bin", "application/octet-stream", payload))
            val blob = storage.blobOf(att)!!
            val token = storage.signedReference(blob, 5.minutes)
            val redirect = assertIs<Delivery.Redirect>(storage.resolveForDelivery(token))

            val conn =
                java.net.URI
                    .create(redirect.url.value)
                    .toURL()
                    .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            val fetched = conn.inputStream.use { it.readBytes() }
            assertContentEquals(payload, fetched)
        }
```

- [ ] **Step 2: Run the end-to-end integration tests**

Run: `./gradlew :integration-tests:integrationTest`
Expected: PASS (requires Docker for MinIO + Postgres).

- [ ] **Step 3: Format and commit**

```bash
./gradlew spotlessApply
git add integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt
git commit -m "test: cover large-payload attach and delivery end to end"
```

---

## Task 5: Full verification

- [ ] **Step 1: Confirm public ABI is unchanged**

Run: `./gradlew apiCheck`
Expected: PASS with no diff — no signatures changed, so the BCV `.api` baselines need no update. (If this fails, the refactor accidentally altered a public signature; revisit rather than regenerating the baseline.)

- [ ] **Step 2: Run lint and the full build**

Run: `./gradlew spotlessCheck apiCheck test`
Expected: PASS.

- [ ] **Step 3: Run the full integration suite**

Run: `./gradlew integrationTest`
Expected: PASS (requires Docker).

- [ ] **Step 4: Final review**

Confirm: no `readByteArray()`/`toByteArray()` full-buffer reads remain on the `spool`, S3 `put`, or S3 `get` paths; FS adapter untouched; no new public API; no BCV baseline change.

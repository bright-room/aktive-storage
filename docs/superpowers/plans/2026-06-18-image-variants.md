# 画像 variant 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 画像 Blob に変換を適用した派生画像を遅延生成・キャッシュし、派生も通常 Blob として既存配信経路に乗せる。

**Architecture:** core に変換の抽象（`Variation` / `Transform` / `VariantProcessor` ポート）と `AktiveStorage.variant()`（遅延生成）を追加。派生は `blobs` 表に入り、`variant_records` で `(origin_blob_id, variation_digest) → variant_blob_id` を追跡。削除ライフサイクルは「派生を孤立回収から除外／元の削除でカスケード purge」で整合をとる。変換実体は新規アダプタ `variant-scrimage`（Scrimage）に隔離する。

**Tech Stack:** Kotlin 2.4 (JVM 21), kotlinx-io, kotlinx-coroutines, Exposed 1.3, Scrimage（バックエンド）, JUnit5 + kotlin.test, binary-compatibility-validator。

---

## 設計上の確定事項（spec からの精緻化）

- **digest の算出場所**: `Variation` は決定的な `canonicalForm: String`（操作列の正規化文字列）を公開する。digest（= variant_records キー・派生ストレージキーの一部）は `AktiveStorage.variant()` 内で**注入済み `Checksum`** からその場で計算する。`Variation` 自身は `Checksum` を持たない。符号化は `kotlin.io.encoding.Base64.UrlSafe`（パディング無し）。
- **派生ストレージキー**: `"<origin.key>/variants/<digest>"`（元と co-located、決定的）。`KeyGenerator` は介さない。
- **元バイトの取得**: `variant()` は `service.get(origin.key)` を一度メモリへ読み（画像は処理時に全展開されるため許容）、`ContentSource` に包んで `VariantProcessor.process` に渡す。`ContentSource.open()` は非 suspend のため、suspend な `service.get` をその場でラップできないことへの対処。
- **保存順序**: `service.put`（派生キーは決定的なので再実行で上書き＝自己修復）→ `metadata.insertVariant`（blob 行 + record を 1 トランザクション）。put 後 insert 前に失敗しても、次回 `variant()` で `findVariant` が null を返し同一キーで再生成され収束する。
- **`MetadataStore` 追加メソッドは 4 つ**: `findVariant` / `insertVariant` / `findVariantsOf` / `deleteVariantsOf`。

## ファイル構成

- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Variant.kt` — `Transform` / `Variation` / 各 enum / `VariantProcessor` ポート
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt` — `MetadataStore` に variant 4 メソッド追加
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt` — `variantProcessor` 引数 + `variant()` + detach/reclaim カスケード
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt` — variant 操作 + 孤立除外
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/FakeVariantProcessor.kt` — 印付けフェイク
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/VariationTest.kt`
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/VariantTest.kt`
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/VariantCascadeTest.kt`
- Modify: `metadata-exposed-jdbc/.../Tables.kt` — `VariantRecordsTable`
- Modify: `metadata-exposed-jdbc/.../ExposedMetadataStore.kt` — variant 実装 + 孤立除外 + createSchema
- Modify: `metadata-exposed-jdbc/.../ExposedMetadataStoreIT.kt` — variant IT
- Create: `variant-scrimage/build.gradle.kts`
- Create: `variant-scrimage/src/main/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessor.kt`
- Create: `variant-scrimage/src/test/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessorTest.kt`
- Modify: `settings.gradle.kts` / `bom/build.gradle.kts` / `gradle/libs.versions.toml`
- Modify: 各 `api/*.api`（apiDump）

---

## Task 1: core に Variation / Transform / VariantProcessor を追加

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Variant.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/VariationTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/net/brightroom/aktivestorage/VariationTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VariationTest {
    @Test
    fun `same transforms produce same canonicalForm`() {
        val a = Variation.of(Transform.Resize(100, 100, ResizeMode.FIT), Transform.Grayscale)
        val b = Variation.of(Transform.Resize(100, 100, ResizeMode.FIT), Transform.Grayscale)
        assertEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `different order produces different canonicalForm`() {
        val a = Variation.of(Transform.Grayscale, Transform.Rotate(90))
        val b = Variation.of(Transform.Rotate(90), Transform.Grayscale)
        assertNotEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `different params produce different canonicalForm`() {
        val a = Variation.of(Transform.Convert(ImageFormat.JPEG, 80))
        val b = Variation.of(Transform.Convert(ImageFormat.JPEG, 90))
        assertNotEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `canonicalForm is stable golden value`() {
        val v = Variation.of(Transform.Resize(200, null, ResizeMode.LIMIT), Transform.Convert(ImageFormat.WEBP, null))
        assertEquals("resize:200x_:LIMIT|convert:WEBP:q_", v.canonicalForm)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariationTest'`
Expected: コンパイルエラー（`Variation` 等が未定義）。

- [ ] **Step 3: 最小実装**

`core/src/main/kotlin/net/brightroom/aktivestorage/Variant.kt`:

```kotlin
package net.brightroom.aktivestorage

/** 画像変換のバックエンド抽象。元画像を読み、variation を適用した結果を返す。 */
public fun interface VariantProcessor {
    /**
     * source を読み、variation を適用した結果を返す。
     * source / 返した ContentSource とも close は呼び出し側責務。
     * 変換は画像全体をメモリ展開する（ストリーミングは適用しない）。
     */
    public suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource
}

/** リサイズの寸法合わせ方。 */
public enum class ResizeMode { FIT, LIMIT, FILL }

/** クロップ基準位置。 */
public enum class Gravity { CENTER, NORTH, SOUTH, EAST, WEST }

/** 出力フォーマット。 */
public enum class ImageFormat { JPEG, PNG, WEBP }

/** 変換操作。 */
public sealed interface Transform {
    public data class Resize(val width: Int?, val height: Int?, val mode: ResizeMode) : Transform

    public data class Crop(val width: Int, val height: Int, val gravity: Gravity) : Transform

    public data class Rotate(val degrees: Int) : Transform

    public data object Grayscale : Transform

    public data class Convert(val format: ImageFormat, val quality: Int?) : Transform
}

/** 変換操作の順序付き列。決定的な canonicalForm を持つ。 */
public class Variation private constructor(
    public val transforms: List<Transform>,
) {
    /** 操作列の安定した正規化文字列。digest 算出（AktiveStorage 側）と同一性判定の基盤。 */
    public val canonicalForm: String = transforms.joinToString("|") { it.canonical() }

    private fun Transform.canonical(): String =
        when (this) {
            is Transform.Resize -> "resize:${width ?: ""}x${height ?: "_"}:$mode"
            is Transform.Crop -> "crop:${width}x$height:$gravity"
            is Transform.Rotate -> "rotate:$degrees"
            Transform.Grayscale -> "grayscale"
            is Transform.Convert -> "convert:$format:q${quality ?: "_"}"
        }

    public companion object {
        public fun of(vararg transforms: Transform): Variation = Variation(transforms.toList())
    }
}
```

注: golden 値 `resize:200x_:LIMIT|convert:WEBP:q_` に厳密一致するよう `canonical()` のフォーマットを上記どおりにすること（Resize の width 有り/height 無しは `200x_`、Convert の quality 無しは `q_`）。

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariationTest'`
Expected: PASS（4 件）。

- [ ] **Step 5: commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Variant.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/VariationTest.kt
git commit -m "feat: add Variation/Transform/VariantProcessor to core"
```

---

## Task 2: MetadataStore に variant 操作を追加（ポート + フェイク）

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`

このタスクは port 拡張とフェイク実装。専用のユニットテストは Task 3/4 で `variant()`・カスケード経由で検証するため、ここでは「ビルドと既存テストが緑」を検証とする。

- [ ] **Step 1: ポートにメソッドを追加**

`Ports.kt` の `MetadataStore` インターフェース末尾（`findAttachmentsForRecord` の後）に追加:

```kotlin
    /** (元 Blob, variation digest) に対応する派生 Blob。無ければ null。 */
    public suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob?

    /** 派生 Blob 行と variant 記録を 1 トランザクションで挿入する。 */
    public suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    )

    /** ある元 Blob に紐づく全派生 Blob。カスケード削除に使う。 */
    public suspend fun findVariantsOf(originBlobId: BlobId): List<Blob>

    /** ある元 Blob の variant 記録と派生 Blob 行をまとめて削除する（実体削除は呼び出し側）。 */
    public suspend fun deleteVariantsOf(originBlobId: BlobId)
```

また `findUnattachedBlobs` の doc を更新（派生は対象外である旨）:

```kotlin
    /** 参照ゼロ かつ createdAt < olderThan の Blob。派生（variant）Blob は対象外。olderThan の猶予で進行中 attach を除外する。 */
    public suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob>
```

- [ ] **Step 2: フェイクを実装**

`InMemoryMetadataStore.kt` を以下に置き換える（variant 記録 `variants` を追加し、`findUnattachedBlobs` で派生を除外）:

```kotlin
package net.brightroom.aktivestorage.fakes

import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef
import kotlin.time.Instant

class InMemoryMetadataStore : MetadataStore {
    val blobs = mutableMapOf<String, Blob>()
    val attachments = mutableMapOf<String, Attachment>()

    // key = "originId|digest" -> variantBlobId
    val variants = mutableMapOf<String, String>()

    private fun variantKey(
        originId: String,
        digest: String,
    ) = "$originId|$digest"

    override suspend fun insertBlob(blob: Blob) {
        blobs[blob.id.value] = blob
    }

    override suspend fun findBlob(id: BlobId): Blob? = blobs[id.value]

    override suspend fun deleteBlob(id: BlobId) {
        blobs.remove(id.value)
    }

    override suspend fun insertAttachment(attachment: Attachment) {
        attachments[attachment.id.value] = attachment
    }

    override suspend fun findAttachments(
        record: RecordRef,
        name: String,
    ): List<Attachment> = attachments.values.filter { it.record == record && it.name == name }

    override suspend fun deleteAttachment(id: AttachmentId) {
        attachments.remove(id.value)
    }

    override suspend fun countAttachmentsForBlob(blobId: BlobId): Int = attachments.values.count { it.blobId == blobId }

    override suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob> {
        val variantBlobIds = variants.values.toSet()
        return blobs.values.filter { blob ->
            blob.createdAt < olderThan &&
                attachments.values.none { it.blobId == blob.id } &&
                blob.id.value !in variantBlobIds
        }
    }

    override suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment> = attachments.values.filter { it.record == record }

    override suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob? = variants[variantKey(originBlobId.value, variationDigest)]?.let { blobs[it] }

    override suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    ) {
        blobs[variant.id.value] = variant
        variants[variantKey(originBlobId.value, variationDigest)] = variant.id.value
    }

    override suspend fun findVariantsOf(originBlobId: BlobId): List<Blob> {
        val prefix = "${originBlobId.value}|"
        return variants.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { blobs[it.value] }
    }

    override suspend fun deleteVariantsOf(originBlobId: BlobId) {
        val prefix = "${originBlobId.value}|"
        val matching = variants.entries.filter { it.key.startsWith(prefix) }
        for (e in matching) {
            blobs.remove(e.value)
            variants.remove(e.key)
        }
    }
}
```

- [ ] **Step 3: ビルドと既存テストが緑**

Run: `./gradlew :core:test`
Expected: PASS（既存テスト全件。port 追加でフェイクがコンパイルできること）。

- [ ] **Step 4: commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt
git commit -m "feat: add variant operations to MetadataStore port and fake"
```

---

## Task 3: AktiveStorage.variant()（遅延生成・再利用）

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/FakeVariantProcessor.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/VariantTest.kt`

- [ ] **Step 1: フェイク VariantProcessor を作る**

`core/src/test/kotlin/net/brightroom/aktivestorage/fakes/FakeVariantProcessor.kt`:

```kotlin
package net.brightroom.aktivestorage.fakes

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Variation
import net.brightroom.aktivestorage.VariantProcessor

/** 入力バイト列に "+variant" を付すだけのフェイク。process 呼び出し回数を数える。 */
class FakeVariantProcessor : VariantProcessor {
    var calls = 0

    override suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource {
        calls++
        val bytes = source.open().buffered().use { it.readByteArray() }
        return ContentSource.ofBytes(
            "variant-${source.filename}",
            source.contentType,
            bytes + "+variant".encodeToByteArray(),
        )
    }
}
```

- [ ] **Step 2: 失敗するテストを書く**

`core/src/test/kotlin/net/brightroom/aktivestorage/VariantTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.fakes.FakeVariantProcessor
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class VariantTest {
    private fun storage(
        service: InMemoryStorageService,
        metadata: InMemoryMetadataStore,
        processor: VariantProcessor? = FakeVariantProcessor(),
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner("k".encodeToByteArray()),
        variantProcessor = processor,
    )

    private suspend fun attachImage(sut: AktiveStorage): Blob {
        val att =
            sut.attach(
                RecordRef("User", "1"),
                "avatar",
                ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()),
            )
        return sut.blobOf(att)!!
    }

    @Test
    fun `variant generates derived blob and stores it`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val origin = attachImage(sut)

            val v = sut.variant(origin, Variation.of(Transform.Grayscale))

            assertEquals(origin.serviceName, v.serviceName)
            assertTrue(v.key.startsWith("${origin.key}/variants/"))
            // 派生実体は元バイト + "+variant"
            assertContentEquals("PNG+variant".encodeToByteArray(), service.objects.getValue(v.key))
        }

    @Test
    fun `variant is reused on second call`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val processor = FakeVariantProcessor()
            val sut = storage(service, metadata, processor)
            val origin = attachImage(sut)
            val variation = Variation.of(Transform.Grayscale)

            val first = sut.variant(origin, variation)
            val second = sut.variant(origin, variation)

            assertEquals(first.id, second.id)
            assertEquals(1, processor.calls) // 2 回目は再生成しない
        }

    @Test
    fun `variant without processor fails`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata, processor = null)
            val origin = attachImage(sut)

            assertFailsWith<IllegalStateException> {
                sut.variant(origin, Variation.of(Transform.Grayscale))
            }
        }

    @Test
    fun `variant blob is deliverable as a normal blob`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val origin = attachImage(sut)
            val v = sut.variant(origin, Variation.of(Transform.Grayscale))

            val token = sut.signedReference(v, 5.minutes)
            val delivery = sut.resolveForDelivery(token)
            assertTrue(delivery is Delivery.Proxy)
            val bytes = (delivery as Delivery.Proxy).stream.buffered().readByteArray()
            assertContentEquals("PNG+variant".encodeToByteArray(), bytes)
        }
}
```

- [ ] **Step 3: テストが失敗することを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariantTest'`
Expected: コンパイルエラー（`variantProcessor` 引数 / `variant()` が未定義）。

- [ ] **Step 4: AktiveStorage を実装**

`AktiveStorage.kt` のコンストラクタに `variantProcessor` を追加（`checksum` の後、`clock` の前）:

```kotlin
public class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val checksum: Checksum = Md5Checksum(),
    private val variantProcessor: VariantProcessor? = null,
    private val clock: Clock = Clock.System,
) {
```

ファイル先頭の import に追加:

```kotlin
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
```

クラスの `@OptIn` を更新:

```kotlin
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
public class AktiveStorage(
```

`blobOf` の後に `variant()` を追加:

```kotlin
    /**
     * blob に variation を適用した派生 Blob を返す（遅延生成）。
     * 既存の variant 記録があればそれを返し、無ければ生成→実体保存→記録して返す。
     * 戻りは通常の Blob で、既存の署名参照/配信経路にそのまま乗る。
     * variantProcessor 未注入時は IllegalStateException。
     */
    public suspend fun variant(
        blob: Blob,
        variation: Variation,
    ): Blob {
        val processor =
            variantProcessor
                ?: error("variant() requires a VariantProcessor; none was injected")
        val digest = digestOf(variation)
        metadata.findVariant(blob.id, digest)?.let { return it }

        val originBytes = service.get(blob.key).buffered().use { it.readByteArray() }
        val origin = ContentSource.ofBytes(blob.filename, blob.contentType, originBytes)
        val processed = processor.process(origin, variation)

        val spooled = spool(processed, checksum)
        try {
            val key = "${blob.key}/variants/$digest"
            val variantBlob =
                Blob(
                    id = BlobId(Uuid.random().toString()),
                    key = key,
                    filename = spooled.filename,
                    contentType = spooled.contentType,
                    byteSize = spooled.byteSize,
                    checksum = spooled.checksumBase64,
                    serviceName = service.name,
                    createdAt = clock.now(),
                )
            service.put(key, spooled, ObjectMetadata(variantBlob.contentType, variantBlob.byteSize, variantBlob.checksum))
            metadata.insertVariant(blob.id, digest, variantBlob)
            return variantBlob
        } finally {
            spooled.cleanup()
        }
    }

    private fun digestOf(variation: Variation): String =
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(checksum.newHasher().also { it.update(variation.canonicalForm.encodeToByteArray()) }.digest())
```

- [ ] **Step 5: テストが通ることを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariantTest'`
Expected: PASS（4 件）。

- [ ] **Step 6: 全 core テストが緑**

Run: `./gradlew :core:test`
Expected: PASS（既存 + 新規）。

- [ ] **Step 7: commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/fakes/FakeVariantProcessor.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/VariantTest.kt
git commit -m "feat: add lazy AktiveStorage.variant() generation and reuse"
```

---

## Task 4: 削除ライフサイクルのカスケード（detach / reclaim）

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/VariantCascadeTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

`core/src/test/kotlin/net/brightroom/aktivestorage/VariantCascadeTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.FakeVariantProcessor
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class VariantCascadeTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_000_000_000_000)

    private fun storage(
        service: InMemoryStorageService,
        metadata: InMemoryMetadataStore,
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner("k".encodeToByteArray()),
        variantProcessor = FakeVariantProcessor(),
        clock = object : Clock { override fun now(): Instant = fixedNow },
    )

    private suspend fun attachWithVariant(
        sut: AktiveStorage,
        metadata: InMemoryMetadataStore,
    ): Pair<Attachment, Blob> {
        val att = sut.attach(RecordRef("User", "1"), "avatar", ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()))
        val origin = sut.blobOf(att)!!
        val v = sut.variant(origin, Variation.of(Transform.Grayscale))
        return att to v
    }

    @Test
    fun `detach purge cascades to variants`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (att, variant) = attachWithVariant(sut, metadata)

            sut.detach(att, purgeBlob = true)

            assertNull(metadata.findBlob(variant.id))
            assertFalse(service.objects.containsKey(variant.key))
            assertTrue(metadata.variants.isEmpty())
        }

    @Test
    fun `reclaim does not reclaim variant blobs of attached origin`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (_, variant) = attachWithVariant(sut, metadata)

            val reclaimed = sut.reclaimUnattached(fixedNow + 1.hours)

            assertEquals(0, reclaimed)
            assertTrue(service.objects.containsKey(variant.key))
            assertTrue(metadata.findBlob(variant.id) != null)
        }

    @Test
    fun `reclaim cascades variants of an orphan origin`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (att, variant) = attachWithVariant(sut, metadata)
            // 添付だけ外して元 Blob を孤立させる（purge せず）
            sut.detach(att, purgeBlob = false)

            val reclaimed = sut.reclaimUnattached(fixedNow + 1.hours)

            assertEquals(1, reclaimed) // 元 1 件
            assertNull(metadata.findBlob(variant.id))
            assertFalse(service.objects.containsKey(variant.key))
        }
}
```

- [ ] **Step 2: テストが失敗することを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariantCascadeTest'`
Expected: FAIL（カスケード未実装。detach 後に variant が残る）。

- [ ] **Step 3: detach と reclaim にカスケードを実装**

`AktiveStorage.kt` の `detach` で、元 Blob 実体・行を消す直前に variant を消す。`detach` の該当部分を以下に置き換える:

```kotlin
    public suspend fun detach(
        attachment: Attachment,
        purgeBlob: Boolean = true,
    ) {
        metadata.deleteAttachment(attachment.id)
        if (!purgeBlob) return
        if (metadata.countAttachmentsForBlob(attachment.blobId) > 0) return
        val blob = metadata.findBlob(attachment.blobId) ?: return
        if (blob.serviceName != service.name) return
        purgeVariantsOf(blob)
        service.delete(blob.key)
        metadata.deleteBlob(blob.id)
    }
```

`reclaimUnattached` のループ本体にもカスケードを追加:

```kotlin
    public suspend fun reclaimUnattached(olderThan: Instant): Int {
        val orphans = metadata.findUnattachedBlobs(olderThan)
        var reclaimed = 0
        for (blob in orphans) {
            if (blob.serviceName != service.name) continue
            purgeVariantsOf(blob)
            service.delete(blob.key)
            metadata.deleteBlob(blob.id)
            reclaimed++
        }
        return reclaimed
    }
```

`purgeRecord` の後に private ヘルパを追加:

```kotlin
    /** 元 Blob に紐づく派生の実体を消し、variant 記録と派生 Blob 行を削除する。 */
    private suspend fun purgeVariantsOf(origin: Blob) {
        for (variant in metadata.findVariantsOf(origin.id)) {
            service.delete(variant.key)
        }
        metadata.deleteVariantsOf(origin.id)
    }
```

- [ ] **Step 4: テストが通ることを確認**

Run: `./gradlew :core:test --tests 'net.brightroom.aktivestorage.VariantCascadeTest'`
Expected: PASS（3 件）。

- [ ] **Step 5: 既存の削除ライフサイクルテストが緑**

Run: `./gradlew :core:test`
Expected: PASS（`DetachRefCountTest` / `ReclaimUnattachedTest` / `PurgeRecordTest` 含め全件）。

- [ ] **Step 6: commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/VariantCascadeTest.kt
git commit -m "feat: cascade variant purge on detach and reclaim"
```

---

## Task 5: Exposed アダプタに variant_records を実装

**Files:**
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/Tables.kt`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`

注: IT は `@Tag("integration")` 付きで testcontainers(PostgreSQL) を使う。`./gradlew :metadata-exposed-jdbc:integrationTest` で実行（Docker 必須）。

- [ ] **Step 1: テーブルを追加**

`Tables.kt` 末尾に追加:

```kotlin
internal object VariantRecordsTable : Table("aktive_variant_records") {
    val originBlobId = varchar("origin_blob_id", 64).references(BlobsTable.id)
    val variationDigest = varchar("variation_digest", 128)
    val variantBlobId = varchar("variant_blob_id", 64).references(BlobsTable.id)
    override val primaryKey = PrimaryKey(originBlobId, variationDigest)

    init {
        index(false, variantBlobId)
    }
}
```

- [ ] **Step 2: 失敗する IT を書く**

`ExposedMetadataStoreIT.kt` の既存クラスにテストを追加（import に `net.brightroom.aktivestorage.Variation` 等は不要 — Blob を直接組む）。`store` / Blob 生成ヘルパは既存 IT の流儀に合わせること。以下のテストを追加:

```kotlin
    @Test
    fun `insert and find variant`() =
        runTest {
            val origin = sampleBlob("origin")
            val variant = sampleBlob("variant")
            store.insertBlob(origin)
            store.insertVariant(origin.id, "digestA", variant)

            assertEquals(variant.id, store.findVariant(origin.id, "digestA")?.id)
            assertNull(store.findVariant(origin.id, "digestB"))
            assertEquals(listOf(variant.id), store.findVariantsOf(origin.id).map { it.id })
        }

    @Test
    fun `unattached sweep excludes variant blobs`() =
        runTest {
            val origin = sampleBlob("origin")
            val variant = sampleBlob("variant")
            store.insertBlob(origin)
            store.insertVariant(origin.id, "digestA", variant)

            val orphans = store.findUnattachedBlobs(Instant.DISTANT_FUTURE)
            // origin は孤立として出るが variant は除外される
            assertTrue(orphans.any { it.id == origin.id })
            assertFalse(orphans.any { it.id == variant.id })
        }

    @Test
    fun `deleteVariantsOf removes records and variant blob rows`() =
        runTest {
            val origin = sampleBlob("origin")
            val variant = sampleBlob("variant")
            store.insertBlob(origin)
            store.insertVariant(origin.id, "digestA", variant)

            store.deleteVariantsOf(origin.id)

            assertNull(store.findVariant(origin.id, "digestA"))
            assertNull(store.findBlob(variant.id))
            assertTrue(store.findVariantsOf(origin.id).isEmpty())
        }
```

`sampleBlob(idSuffix)` ヘルパが既存 IT に無ければ、既存テストが Blob を組んでいる方法に合わせてクラス先頭に追加する（`Blob(BlobId("blob-$idSuffix"), key="key-$idSuffix", filename="f", contentType="image/png", byteSize=1, checksum="c", serviceName="s3", createdAt=Instant.fromEpochMilliseconds(0))` 相当）。各テスト前に全テーブルを truncate する既存の `@BeforeEach` 規約（`ExposedMetadataStoreIT` 既存）に `VariantRecordsTable` を含めること。

- [ ] **Step 3: IT が失敗することを確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest --tests '*ExposedMetadataStoreIT*'`
Expected: コンパイルエラー（variant メソッド未実装）。

- [ ] **Step 4: 実装する**

`ExposedMetadataStore.kt`:

`createSchema()` を更新:

```kotlin
    public fun createSchema() {
        transaction(db) { SchemaUtils.create(BlobsTable, AttachmentsTable, VariantRecordsTable) }
    }
```

import に追加: `import org.jetbrains.exposed.v1.core.innerJoin`（必要に応じて）。

`findUnattachedBlobs` を派生除外に更新:

```kotlin
    override suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob> =
        dbQuery {
            val cutoff = olderThan.toEpochMilliseconds()
            val variantBlobIds =
                VariantRecordsTable
                    .selectAll()
                    .map { it[VariantRecordsTable.variantBlobId] }
                    .toSet()
            (BlobsTable leftJoin AttachmentsTable)
                .selectAll()
                .where { AttachmentsTable.id.isNull() and (BlobsTable.createdAt less cutoff) }
                .map { it.toBlob() }
                .filterNot { it.id.value in variantBlobIds }
        }
```

`findAttachmentsForRecord` の後に variant 実装を追加:

```kotlin
    override suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob? =
        dbQuery {
            (BlobsTable innerJoin VariantRecordsTable)
                .selectAll()
                .where {
                    (VariantRecordsTable.originBlobId eq originBlobId.value) and
                        (VariantRecordsTable.variationDigest eq variationDigest) and
                        (BlobsTable.id eq VariantRecordsTable.variantBlobId)
                }.singleOrNull()
                ?.toBlob()
        }

    override suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    ): Unit =
        dbQuery {
            BlobsTable.insert {
                it[id] = variant.id.value
                it[key] = variant.key
                it[filename] = variant.filename
                it[contentType] = variant.contentType
                it[byteSize] = variant.byteSize
                it[checksum] = variant.checksum
                it[serviceName] = variant.serviceName
                it[createdAt] = variant.createdAt.toEpochMilliseconds()
            }
            VariantRecordsTable.insert {
                it[VariantRecordsTable.originBlobId] = originBlobId.value
                it[VariantRecordsTable.variationDigest] = variationDigest
                it[variantBlobId] = variant.id.value
            }
            Unit
        }

    override suspend fun findVariantsOf(originBlobId: BlobId): List<Blob> =
        dbQuery {
            (BlobsTable innerJoin VariantRecordsTable)
                .selectAll()
                .where {
                    (VariantRecordsTable.originBlobId eq originBlobId.value) and
                        (BlobsTable.id eq VariantRecordsTable.variantBlobId)
                }.map { it.toBlob() }
        }

    override suspend fun deleteVariantsOf(originBlobId: BlobId): Unit =
        dbQuery {
            val variantIds =
                VariantRecordsTable
                    .selectAll()
                    .where { VariantRecordsTable.originBlobId eq originBlobId.value }
                    .map { it[VariantRecordsTable.variantBlobId] }
            VariantRecordsTable.deleteWhere { VariantRecordsTable.originBlobId eq originBlobId.value }
            for (vid in variantIds) {
                BlobsTable.deleteWhere { BlobsTable.id eq vid }
            }
            Unit
        }
```

注: `innerJoin` の結合条件を where に書く形（既存 `leftJoin` 流儀に合わせた）。`BlobsTable innerJoin VariantRecordsTable` が外部キー（`variantBlobId` または `originBlobId`）で自動結合される場合は二重結合になりうるため、コンパイル後に IT で結合結果（1 行）を必ず確認すること。曖昧なら明示結合 `BlobsTable.join(VariantRecordsTable, JoinType.INNER, onColumn = BlobsTable.id, otherColumn = VariantRecordsTable.variantBlobId)` に切り替える。

- [ ] **Step 5: IT が通ることを確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest --tests '*ExposedMetadataStoreIT*'`
Expected: PASS（既存 + 新規 3 件）。Docker 未起動なら起動してから実行。

- [ ] **Step 6: commit**

```bash
git add metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/Tables.kt \
        metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt \
        metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt
git commit -m "feat: implement variant_records in Exposed metadata store"
```

---

## Task 6: variant-scrimage モジュール（Scrimage 実装）

**Files:**
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`, `bom/build.gradle.kts`
- Create: `variant-scrimage/build.gradle.kts`
- Create: `variant-scrimage/src/main/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessor.kt`
- Test: `variant-scrimage/src/test/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessorTest.kt`

- [ ] **Step 1: Scrimage の座標とバージョンを確認**

`com.sksamuel.scrimage:scrimage-core` の最新安定版を Maven Central で確認する（推測しない）。WebP 出力に必要なアーティファクト（`scrimage-webp` 等）の座標も併せて確認する。確認したバージョンを記録して次へ。

- [ ] **Step 2: 依存を登録**

`gradle/libs.versions.toml` の `[versions]` に追加（`<確認したバージョン>` を Step 1 の値に置換）:

```toml
scrimage = "<確認したバージョン>"
```

`[libraries]` に追加:

```toml
scrimage-core = { module = "com.sksamuel.scrimage:scrimage-core", version.ref = "scrimage" }
scrimage-webp = { module = "com.sksamuel.scrimage:scrimage-webp", version.ref = "scrimage" }
```

`settings.gradle.kts` の include 群に追加（`metadata-exposed-jdbc` の後）:

```kotlin
include("variant-scrimage")
```

`bom/build.gradle.kts` の constraints に追加:

```kotlin
        api(project(":variant-scrimage"))
```

- [ ] **Step 3: モジュールの build スクリプトを作る**

`variant-scrimage/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
    id("aktive.published")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.scrimage.core)
    implementation(libs.scrimage.webp)
}
```

- [ ] **Step 4: 失敗するテストを書く**

`variant-scrimage/src/test/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessorTest.kt`:

```kotlin
package net.brightroom.aktivestorage.variant.scrimage

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ImageFormat
import net.brightroom.aktivestorage.ResizeMode
import net.brightroom.aktivestorage.Transform
import net.brightroom.aktivestorage.Variation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrimageVariantProcessorTest {
    private val processor = ScrimageVariantProcessor()

    /** 単色 60x40 PNG を作る。 */
    private fun samplePng(): ByteArray {
        val image = ImmutableImage.create(60, 40)
        return image.bytes(PngWriter.MaxCompression)
    }

    private fun source(bytes: ByteArray) = ContentSource.ofBytes("a.png", "image/png", bytes)

    private suspend fun decode(cs: ContentSource): ImmutableImage =
        ImmutableImage.loader().fromBytes(cs.open().buffered().readByteArray())

    @Test
    fun `resize fit bounds within box`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Resize(30, 30, ResizeMode.FIT)))
            val img = decode(out)
            assertTrue(img.width <= 30 && img.height <= 30)
        }

    @Test
    fun `crop produces exact dimensions`() =
        runTest {
            val out =
                processor.process(
                    source(samplePng()),
                    Variation.of(Transform.Crop(20, 20, net.brightroom.aktivestorage.Gravity.CENTER)),
                )
            val img = decode(out)
            assertEquals(20, img.width)
            assertEquals(20, img.height)
        }

    @Test
    fun `convert to jpeg sets content type`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Convert(ImageFormat.JPEG, 80)))
            assertEquals("image/jpeg", out.contentType)
        }

    @Test
    fun `rotate 90 swaps dimensions`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Rotate(90)))
            val img = decode(out)
            assertEquals(40, img.width)
            assertEquals(60, img.height)
        }

    @Test
    fun `grayscale keeps dimensions`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Grayscale))
            val img = decode(out)
            assertEquals(60, img.width)
            assertEquals(40, img.height)
        }
}
```

- [ ] **Step 5: テストが失敗することを確認**

Run: `./gradlew :variant-scrimage:test`
Expected: コンパイルエラー（`ScrimageVariantProcessor` 未定義）。

- [ ] **Step 6: ScrimageVariantProcessor を実装**

`variant-scrimage/src/main/kotlin/net/brightroom/aktivestorage/variant/scrimage/ScrimageVariantProcessor.kt`:

```kotlin
package net.brightroom.aktivestorage.variant.scrimage

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.webp.WebpWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Gravity
import net.brightroom.aktivestorage.ImageFormat
import net.brightroom.aktivestorage.ResizeMode
import net.brightroom.aktivestorage.Transform
import net.brightroom.aktivestorage.Variation
import net.brightroom.aktivestorage.VariantProcessor

/** Scrimage を使った VariantProcessor 実装。変換は画像全体をメモリ展開する。 */
public class ScrimageVariantProcessor : VariantProcessor {
    override suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource =
        withContext(Dispatchers.IO) {
            val bytes = source.open().buffered().use { it.readByteArray() }
            var image = ImmutableImage.loader().fromBytes(bytes)
            var format: ImageFormat? = null
            var quality: Int? = null

            for (t in variation.transforms) {
                when (t) {
                    is Transform.Resize -> image = image.applyResize(t)
                    is Transform.Crop -> image = image.applyCrop(t)
                    is Transform.Rotate -> image = image.applyRotate(t.degrees)
                    Transform.Grayscale -> image = image.toGrayscale()
                    is Transform.Convert -> {
                        format = t.format
                        quality = t.quality
                    }
                }
            }

            val outFormat = format ?: defaultFormat(source.contentType)
            val outBytes = image.encode(outFormat, quality)
            ContentSource.ofBytes(
                rename(source.filename, outFormat),
                contentTypeOf(outFormat),
                outBytes,
            )
        }

    private fun ImmutableImage.applyResize(t: Transform.Resize): ImmutableImage =
        when (t.mode) {
            // FIT/LIMIT は box 内に収める（アスペクト比保持）。LIMIT は拡大しない。
            ResizeMode.FIT -> max(t.width ?: width, t.height ?: height)
            ResizeMode.LIMIT -> bound(t.width ?: width, t.height ?: height)
            // FILL は box を覆うようスケール + 中央クロップ。
            ResizeMode.FILL -> cover(t.width ?: width, t.height ?: height)
        }

    private fun ImmutableImage.applyCrop(t: Transform.Crop): ImmutableImage {
        val x =
            when (t.gravity) {
                Gravity.WEST -> 0
                Gravity.EAST -> (width - t.width).coerceAtLeast(0)
                else -> ((width - t.width) / 2).coerceAtLeast(0)
            }
        val y =
            when (t.gravity) {
                Gravity.NORTH -> 0
                Gravity.SOUTH -> (height - t.height).coerceAtLeast(0)
                else -> ((height - t.height) / 2).coerceAtLeast(0)
            }
        return subimage(x, y, t.width.coerceAtMost(width), t.height.coerceAtMost(height))
    }

    private fun ImmutableImage.applyRotate(degrees: Int): ImmutableImage =
        when (((degrees % 360) + 360) % 360) {
            90 -> rotateRight()
            180 -> rotateRight().rotateRight()
            270 -> rotateLeft()
            else -> this
        }

    private fun ImmutableImage.encode(
        format: ImageFormat,
        quality: Int?,
    ): ByteArray =
        when (format) {
            ImageFormat.JPEG -> bytes(JpegWriter().withCompression(quality ?: 80))
            ImageFormat.PNG -> bytes(PngWriter.MaxCompression)
            ImageFormat.WEBP -> bytes(WebpWriter.DEFAULT.let { if (quality != null) it.withQ(quality) else it })
        }

    private fun defaultFormat(contentType: String): ImageFormat =
        when (contentType.lowercase()) {
            "image/jpeg", "image/jpg" -> ImageFormat.JPEG
            "image/webp" -> ImageFormat.WEBP
            else -> ImageFormat.PNG
        }

    private fun contentTypeOf(format: ImageFormat): String =
        when (format) {
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.PNG -> "image/png"
            ImageFormat.WEBP -> "image/webp"
        }

    private fun rename(
        filename: String,
        format: ImageFormat,
    ): String {
        val base = filename.substringBeforeLast('.', filename)
        val ext = when (format) {
            ImageFormat.JPEG -> "jpg"
            ImageFormat.PNG -> "png"
            ImageFormat.WEBP -> "webp"
        }
        return "$base.$ext"
    }
}
```

注（Step 1 の確認結果に合わせて要調整。これらは Scrimage の典型 API だがバージョンで差異がありうる）:
- `max` / `bound` / `cover` / `subimage` / `rotateRight` / `rotateLeft` / `toGrayscale` のメソッド名・引数はピン留めしたバージョンの API に合わせる。
- WebP writer の座標・クラス名（`com.sksamuel.scrimage.webp.WebpWriter` / `withQ`）も確認する。WebP がネイティブ依存で CI 環境に載らない場合、WebP テストは別 `@Tag` にするか OS 条件で skip する判断を行う（その場合 `log` 相当のコメントを残す）。
- JpegWriter の品質指定 API（`withCompression` 等）も確認する。

- [ ] **Step 7: テストが通ることを確認**

Run: `./gradlew :variant-scrimage:test`
Expected: PASS（5 件）。失敗時は Step 6 の注に従い Scrimage API 名を修正。

- [ ] **Step 8: commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts bom/build.gradle.kts variant-scrimage
git commit -m "feat: add variant-scrimage adapter (Scrimage VariantProcessor)"
```

---

## Task 7: ABI ダンプ更新と全体検証

**Files:**
- Modify: `core/api/core.api`, `metadata-exposed-jdbc/api/metadata-exposed-jdbc.api`, `variant-scrimage/api/variant-scrimage.api`

- [ ] **Step 1: apiDump を再生成**

Run: `./gradlew apiDump`
Expected: `core` に `Variation` / `Transform` / `VariantProcessor` / enum 群と `AktiveStorage.variant` / コンストラクタ変更、`MetadataStore` の 4 メソッドが反映。`variant-scrimage/api/variant-scrimage.api` が新規生成。`metadata-exposed-jdbc` は内部実装のみのため差分が出ないこと（出たら確認）。

- [ ] **Step 2: 差分をレビュー**

Run: `git diff --stat -- '*.api'`
Expected: 追加のみで意図しない削除・変更が無いこと。

- [ ] **Step 3: apiCheck が通ることを確認**

Run: `./gradlew apiCheck`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 全体ビルド + ユニットテスト**

Run: `./gradlew build -x integrationTest`
Expected: BUILD SUCCESSFUL（spotless / explicitApi 含む）。IT は Docker 前提のため別途実行済み（Task 5）。

- [ ] **Step 5: commit**

```bash
git add '*.api'
git commit -m "build: regenerate ABI baselines for image variants"
```

---

## 完了の定義

- `./gradlew build -x integrationTest` が緑（spotless / explicitApi / apiCheck / 全ユニットテスト）。
- `./gradlew :metadata-exposed-jdbc:integrationTest` が緑（Docker 環境）。
- `variant()` の遅延生成・再利用・配信・カスケード削除・孤立非回収がテストで担保されている。
- `core` / `variant-scrimage` の `api/*.api` が追加分を反映し、意図しない差分が無い。
- バージョン方針に従い次回リリースで minor bump（バージョン操作自体はリリースフローのスコープ。本計画では行わない）。

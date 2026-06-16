# 削除ライフサイクル / 孤立 Blob 回収 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `detach` を参照カウント安全にし、猶予付きの孤立 Blob 掃除（`reclaimUnattached`）とレコード単位の一括 purge（`purgeRecord`）を core に追加する。

**Architecture:** `MetadataStore` ポートに参照カウント・孤立発見・レコード単位一覧の 3 操作を足し、`AktiveStorage` がそれらを使って安全削除を組み立てる。削除順序は「実体 → Blob 行」に統一し、冪等 delete 前提で再実行可能にする。掃除をいつ走らせるかは利用者に委ねる（job 非依存）。

**Tech Stack:** Kotlin / coroutine, kotlinx-io, Exposed v1 (JDBC), JUnit5 + kotlin.test, Testcontainers (PostgreSQL / MinIO)。

**Spec:** `docs/superpowers/specs/2026-06-16-deletion-lifecycle-design.md`

---

## ファイル構成

- `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt` — `MetadataStore` に 3 メソッド追加、`StorageService.delete` の冪等性契約を KDoc 明記（修正）
- `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt` — `detach` 改修、`reclaimUnattached` / `purgeRecord` 追加（修正）
- `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt` — 追加 3 メソッドを実装（修正）
- `core/src/test/kotlin/net/brightroom/aktivestorage/DetachRefCountTest.kt` — 参照カウント detach（新規）
- `core/src/test/kotlin/net/brightroom/aktivestorage/ReclaimUnattachedTest.kt` — 孤立掃除（新規）
- `core/src/test/kotlin/net/brightroom/aktivestorage/PurgeRecordTest.kt` — レコード単位 purge（新規）
- `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt` — 追加 3 メソッドを実装（修正）
- `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt` — 追加 3 メソッドの SQL 検証（修正）
- `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt` — 削除ライフサイクルの E2E（修正）
- `core/api/core.api`, `metadata-exposed-jdbc/api/metadata-exposed-jdbc.api` — ABI ベースライン再生成（修正）

## 注意（ビルドの前提）

- `aktive.kotlin-library` は通常の `test` タスクから `@Tag("integration")` を除外する。`apiCheck` は `check` に紐づき `test` では走らない。よって各タスクで走らせる `:module:test` / `:module:integrationTest` は ABI ドリフトで落ちない。`apiDump` は **最終タスクでまとめて**行う。
- Kotlin（静的型）では「失敗するテスト」の RED は多くの場合**コンパイルエラー（unresolved reference）**として現れる。これを RED とみなしてよい。
- インターフェース（`MetadataStore`）にメソッドを足すと実装側（`InMemoryMetadataStore` / `ExposedMetadataStore`）がコンパイル不能になるため、メソッド追加と両実装を**同一タスク内**で行い、各コミット時点でコンパイルが緑になるようにする。

---

### Task 1: `StorageService.delete` の冪等性契約を明記

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt:22`

ドキュメントのみ。後続タスク（reclaim / detach）の「実体 → 行」順の再実行可能性が、この契約に依存する。既存実装が既に契約を満たすことを確認する。

- [ ] **Step 1: 既存実装が冪等であることを確認**

確認内容（コード変更不要であることの裏取り）:
- `storage-fs/src/main/kotlin/.../FilesystemStorageService.kt:45-46` の `delete` は `SystemFileSystem.delete(resolveSafe(key), mustExist = false)` で、存在しないキーでも例外を投げない（冪等）。
- `storage-s3/src/main/kotlin/.../S3StorageService.kt:73-78` の `delete` は S3 `deleteObject` で、存在しないキーでも成功扱い（冪等）。

両者とも既に契約を満たすため、本タスクは KDoc 追記のみ。

- [ ] **Step 2: `delete` の KDoc を追記**

`Ports.kt` の `StorageService.delete` を次へ変更する。

```kotlin
    /** キーに対応する実体を削除する。存在しないキーに対しては no-op（冪等）。 */
    public suspend fun delete(key: String)
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt
git commit -m "$(cat <<'EOF'
docs: document StorageService.delete idempotency contract

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `countAttachmentsForBlob` ポート + 実装

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`（`MetadataStore` にメソッド追加）
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`

- [ ] **Step 1: 失敗するテストを書く**

`ExposedMetadataStoreIT.kt` に追加（import に `assertEquals` は既存）:

```kotlin
    @Test
    fun `countAttachmentsForBlob counts only matching attachments`() =
        runBlocking {
            store.insertBlob(blob("bc", "kc"))
            val record = RecordRef("User", "count")
            store.insertAttachment(Attachment(AttachmentId("ac1"), "avatar", record, BlobId("bc"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ac2"), "cover", record, BlobId("bc"), Instant.fromEpochMilliseconds(0)))
            assertEquals(2, store.countAttachmentsForBlob(BlobId("bc")))

            store.deleteAttachment(AttachmentId("ac1"))
            assertEquals(1, store.countAttachmentsForBlob(BlobId("bc")))

            assertEquals(0, store.countAttachmentsForBlob(BlobId("no-such-blob")))
        }
```

- [ ] **Step 2: RED を確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: コンパイル失敗 `unresolved reference: countAttachmentsForBlob`

- [ ] **Step 3: ポートにメソッドを追加**

`Ports.kt` の `MetadataStore` インターフェース末尾（`deleteAttachment` の後）に追加:

```kotlin
    /** ある Blob を参照する Attachment 数。参照カウント安全 purge と孤立判定の基盤。 */
    public suspend fun countAttachmentsForBlob(blobId: BlobId): Int
```

- [ ] **Step 4: フェイクに実装**

`InMemoryMetadataStore.kt` の `deleteAttachment` の後に追加:

```kotlin
    override suspend fun countAttachmentsForBlob(blobId: BlobId): Int = attachments.values.count { it.blobId == blobId }
```

- [ ] **Step 5: Exposed に実装**

`ExposedMetadataStore.kt` の `deleteAttachment` の後に追加:

```kotlin
    override suspend fun countAttachmentsForBlob(blobId: BlobId): Int =
        dbQuery {
            AttachmentsTable
                .selectAll()
                .where { AttachmentsTable.blobId eq blobId.value }
                .count()
                .toInt()
        }
```

- [ ] **Step 6: GREEN を確認**

Run: `./gradlew :core:test :metadata-exposed-jdbc:integrationTest`
Expected: PASS（core はコンパイル＋既存テスト緑、metadata は新テスト緑）

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt \
        metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt \
        metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt
git commit -m "$(cat <<'EOF'
feat: add MetadataStore.countAttachmentsForBlob

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `detach` を参照カウント安全にする

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt:69-78`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/DetachRefCountTest.kt`（新規）

`attach()` は毎回新規 Blob を作るため、共有 Blob は 2 つ目の Attachment をフェイクに直接挿入して再現する。

- [ ] **Step 1: 失敗するテストを書く**

新規ファイル `core/src/test/kotlin/net/brightroom/aktivestorage/DetachRefCountTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DetachRefCountTest {
    private val record = RecordRef("User", "42")

    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    @Test
    fun `detach keeps blob while another attachment still references it`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val att1 = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val blob = m.findBlob(att1.blobId)!!
            // 同じ Blob を参照する 2 つ目の Attachment を直接挿入（共有を再現）
            val att2 = Attachment(AttachmentId("att2"), "cover", record, blob.id, Instant.fromEpochMilliseconds(0))
            m.insertAttachment(att2)

            st.detach(att1, purgeBlob = true)

            // att2 がまだ参照しているので Blob と実体は残る
            assertNotNull(m.findBlob(blob.id))
            assertTrue(s.exists(blob.key))
        }

    @Test
    fun `detach purges blob when last reference is removed`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val att1 = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val blob = m.findBlob(att1.blobId)!!
            val att2 = Attachment(AttachmentId("att2"), "cover", record, blob.id, Instant.fromEpochMilliseconds(0))
            m.insertAttachment(att2)

            st.detach(att1, purgeBlob = true)
            st.detach(att2, purgeBlob = true)

            // 最後の参照を外したので Blob と実体が消える
            assertNull(m.findBlob(blob.id))
            assertFalse(s.exists(blob.key))
        }
}
```

- [ ] **Step 2: RED を確認**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.DetachRefCountTest"`
Expected: FAIL（1 つ目のテストで Blob が消えてしまい `assertNotNull` が失敗。現 `detach` は無条件 purge のため）

- [ ] **Step 3: `detach` を改修**

`AktiveStorage.kt` の `detach`（69-78 行）を次へ置換する。既存の「MVP は参照カウントしない」コメントも更新する。

```kotlin
    /**
     * 添付を外す。purgeBlob=true でも、その Blob を参照する他の Attachment が
     * 残っている場合は Blob 行・実体を残す（参照カウント安全）。
     * 実体 → Blob 行 の順で削除し、冪等 delete 前提で再実行可能にする。
     */
    public suspend fun detach(
        attachment: Attachment,
        purgeBlob: Boolean = true,
    ) {
        metadata.deleteAttachment(attachment.id)
        if (!purgeBlob) return
        if (metadata.countAttachmentsForBlob(attachment.blobId) > 0) return
        val blob = metadata.findBlob(attachment.blobId) ?: return
        service.delete(blob.key)
        metadata.deleteBlob(blob.id)
    }
```

- [ ] **Step 4: GREEN を確認**

Run: `./gradlew :core:test`
Expected: PASS（`DetachRefCountTest` と既存 `AttachmentsAndDetachTest` の両方が緑。後者は単一参照のため従来どおり purge される）

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/DetachRefCountTest.kt
git commit -m "$(cat <<'EOF'
feat: make detach reference-count safe

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `findUnattachedBlobs(olderThan)` ポート + 実装

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`

- [ ] **Step 1: 失敗するテストを書く**

`ExposedMetadataStoreIT.kt` に追加。`blob(id, key)` ヘルパは `createdAt=epoch 0` 固定なので、createdAt を指定できるよう別途インラインで Blob を作る。

```kotlin
    @Test
    fun `findUnattachedBlobs returns only old blobs with no attachment`() =
        runBlocking {
            // 古い孤立 Blob（createdAt=100, 参照なし）
            store.insertBlob(
                Blob(BlobId("u-old"), "ku-old", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(100)),
            )
            // 新しい孤立 Blob（createdAt=5000, 参照なし → 猶予内なので対象外）
            store.insertBlob(
                Blob(BlobId("u-new"), "ku-new", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(5000)),
            )
            // 古いが紐付きの Blob（createdAt=100, 参照あり → 対象外）
            store.insertBlob(
                Blob(BlobId("u-att"), "ku-att", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(100)),
            )
            store.insertAttachment(
                Attachment(AttachmentId("ua1"), "avatar", RecordRef("User", "u"), BlobId("u-att"), Instant.fromEpochMilliseconds(100)),
            )

            val found = store.findUnattachedBlobs(Instant.fromEpochMilliseconds(1000)).map { it.id.value }.toSet()

            assertEquals(setOf("u-old"), found)
        }
```

import に `assertEquals`（既存）。`Blob` import は既存。

- [ ] **Step 2: RED を確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: コンパイル失敗 `unresolved reference: findUnattachedBlobs`

- [ ] **Step 3: ポートにメソッドを追加**

`Ports.kt` の `MetadataStore` に追加（先頭付近の import に `kotlin.time.Instant` は既存）:

```kotlin
    /** 参照ゼロ かつ createdAt < olderThan の Blob。olderThan の猶予で進行中 attach を除外する。 */
    public suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob>
```

- [ ] **Step 4: フェイクに実装**

`InMemoryMetadataStore.kt` に追加（import に `kotlin.time.Instant` を追加）:

```kotlin
    override suspend fun findUnattachedBlobs(olderThan: kotlin.time.Instant): List<Blob> =
        blobs.values.filter { blob ->
            blob.createdAt < olderThan && attachments.values.none { it.blobId == blob.id }
        }
```

- [ ] **Step 5: Exposed に実装**

`ExposedMetadataStore.kt` に追加。`BlobsTable leftJoin AttachmentsTable` で「Attachment 側が NULL（＝参照なし）」かつ `createdAt < cutoff` を抽出する。参照なし行は join 後も 1 行なので重複しない。

```kotlin
    override suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob> =
        dbQuery {
            val cutoff = olderThan.toEpochMilliseconds()
            (BlobsTable leftJoin AttachmentsTable)
                .selectAll()
                .where { AttachmentsTable.id.isNull() and (BlobsTable.createdAt less cutoff) }
                .map { it.toBlob() }
        }
```

import を追加（Exposed v1 の正確なパッケージは実装時に確認。候補）:
- `org.jetbrains.exposed.v1.core.leftJoin`
- `org.jetbrains.exposed.v1.core.isNull`（または `Column.isNull()` 拡張）
- `org.jetbrains.exposed.v1.core.less`

`toBlob()` は `BlobsTable` の列のみ読むため leftJoin の `ResultRow` でそのまま機能する。

- [ ] **Step 6: GREEN を確認**

Run: `./gradlew :core:test :metadata-exposed-jdbc:integrationTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt \
        metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt \
        metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt
git commit -m "$(cat <<'EOF'
feat: add MetadataStore.findUnattachedBlobs with grace cutoff

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `reclaimUnattached(olderThan)` を core に追加

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/ReclaimUnattachedTest.kt`（新規）

- [ ] **Step 1: 失敗するテストを書く**

新規ファイル `core/src/test/kotlin/net/brightroom/aktivestorage/ReclaimUnattachedTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReclaimUnattachedTest {
    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    private fun blob(
        id: String,
        key: String,
        createdAtMillis: Long,
    ) = Blob(BlobId(id), key, "f", "image/png", 1, "c", "memory", Instant.fromEpochMilliseconds(createdAtMillis))

    @Test
    fun `reclaimUnattached removes old orphan blobs and their objects`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)

            // 古い孤立（対象）
            m.insertBlob(blob("old", "k-old", 100))
            s.objects["k-old"] = "x".encodeToByteArray()
            // 新しい孤立（猶予内 → 対象外）
            m.insertBlob(blob("new", "k-new", 5000))
            s.objects["k-new"] = "y".encodeToByteArray()
            // 紐付き（対象外）
            m.insertBlob(blob("att", "k-att", 100))
            s.objects["k-att"] = "z".encodeToByteArray()
            m.insertAttachment(Attachment(AttachmentId("a"), "avatar", RecordRef("User", "u"), BlobId("att"), Instant.fromEpochMilliseconds(100)))

            val reclaimed = st.reclaimUnattached(Instant.fromEpochMilliseconds(1000))

            assertEquals(1, reclaimed)
            assertFalse(s.exists("k-old"))           // 実体削除
            assertEquals(null, m.findBlob(BlobId("old"))) // 行削除
            assertTrue(s.exists("k-new"))            // 猶予内は残る
            assertNotNull(m.findBlob(BlobId("new")))
            assertTrue(s.exists("k-att"))            // 紐付きは残る
            assertNotNull(m.findBlob(BlobId("att")))
        }
}
```

- [ ] **Step 2: RED を確認**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.ReclaimUnattachedTest"`
Expected: コンパイル失敗 `unresolved reference: reclaimUnattached`

- [ ] **Step 3: `reclaimUnattached` を実装**

`AktiveStorage.kt` の `detach` の後に追加（import に `kotlin.time.Instant` を追加）:

```kotlin
    /**
     * 参照ゼロ かつ olderThan より前に作られた Blob を回収し、回収できた件数を返す。
     * olderThan は呼び出し側が `now - grace` として渡し、進行中の attach を除外する。
     * 実体 → Blob 行 の順で削除する。途中失敗時は例外を伝播し、削除済み分は確定する
     * （冪等 delete 前提で再実行すれば残りを処理して収束する）。
     * いつ走らせるかは利用者の責務（このライブラリは job を持たない）。
     */
    public suspend fun reclaimUnattached(olderThan: Instant): Int {
        val orphans = metadata.findUnattachedBlobs(olderThan)
        for (blob in orphans) {
            service.delete(blob.key)
            metadata.deleteBlob(blob.id)
        }
        return orphans.size
    }
```

`Instant` の import が未追加なら `import kotlin.time.Instant` を追加する。

- [ ] **Step 4: GREEN を確認**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/ReclaimUnattachedTest.kt
git commit -m "$(cat <<'EOF'
feat: add reclaimUnattached for grace-bounded orphan blob sweep

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `findAttachmentsForRecord(record)` ポート + 実装

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`
- Modify: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`
- Modify: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`

- [ ] **Step 1: 失敗するテストを書く**

`ExposedMetadataStoreIT.kt` に追加:

```kotlin
    @Test
    fun `findAttachmentsForRecord returns all names for the record only`() =
        runBlocking {
            store.insertBlob(blob("br1", "kr1"))
            store.insertBlob(blob("br2", "kr2"))
            store.insertBlob(blob("br3", "kr3"))
            val target = RecordRef("User", "rec")
            val other = RecordRef("User", "other")
            store.insertAttachment(Attachment(AttachmentId("ar1"), "avatar", target, BlobId("br1"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ar2"), "cover", target, BlobId("br2"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ar3"), "avatar", other, BlobId("br3"), Instant.fromEpochMilliseconds(0)))

            val names = store.findAttachmentsForRecord(target).map { it.name }.toSet()

            assertEquals(setOf("avatar", "cover"), names)
        }
```

- [ ] **Step 2: RED を確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: コンパイル失敗 `unresolved reference: findAttachmentsForRecord`

- [ ] **Step 3: ポートにメソッドを追加**

`Ports.kt` の `MetadataStore` に追加:

```kotlin
    /** name を問わずレコードの全添付。レコード削除との連動（一括 purge）に使う。 */
    public suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment>
```

- [ ] **Step 4: フェイクに実装**

`InMemoryMetadataStore.kt` に追加:

```kotlin
    override suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment> =
        attachments.values.filter { it.record == record }
```

- [ ] **Step 5: Exposed に実装**

`ExposedMetadataStore.kt` に追加:

```kotlin
    override suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment> =
        dbQuery {
            AttachmentsTable
                .selectAll()
                .where {
                    (AttachmentsTable.recordType eq record.type) and
                        (AttachmentsTable.recordId eq record.id)
                }.map { it.toAttachment() }
        }
```

- [ ] **Step 6: GREEN を確認**

Run: `./gradlew :core:test :metadata-exposed-jdbc:integrationTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt \
        metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt \
        metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt
git commit -m "$(cat <<'EOF'
feat: add MetadataStore.findAttachmentsForRecord

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `purgeRecord(record)` を core に追加

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/PurgeRecordTest.kt`（新規）

- [ ] **Step 1: 失敗するテストを書く**

新規ファイル `core/src/test/kotlin/net/brightroom/aktivestorage/PurgeRecordTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PurgeRecordTest {
    private val record = RecordRef("User", "42")

    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    @Test
    fun `purgeRecord detaches and purges all attachments of a record`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val avatar = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val doc = st.attach(record, "documents", ContentSource.ofBytes("d", "text/plain", "y".encodeToByteArray()))
            // 別レコードの添付は残るべき
            val other = st.attach(RecordRef("User", "99"), "avatar", ContentSource.ofBytes("o", "text/plain", "z".encodeToByteArray()))
            val avatarKey = m.findBlob(avatar.blobId)!!.key
            val docKey = m.findBlob(doc.blobId)!!.key

            st.purgeRecord(record)

            assertEquals(0, st.attachments(record, "avatar").size)
            assertEquals(0, st.attachments(record, "documents").size)
            assertFalse(s.exists(avatarKey))
            assertFalse(s.exists(docKey))
            // 別レコードは無傷
            assertEquals(1, st.attachments(RecordRef("User", "99"), "avatar").size)
        }
}
```

- [ ] **Step 2: RED を確認**

Run: `./gradlew :core:test --tests "net.brightroom.aktivestorage.PurgeRecordTest"`
Expected: コンパイル失敗 `unresolved reference: purgeRecord`

- [ ] **Step 3: `purgeRecord` を実装**

`AktiveStorage.kt` の `reclaimUnattached` の後に追加:

```kotlin
    /** レコードの全添付（name 問わず）を参照カウント安全に detach+purge する。 */
    public suspend fun purgeRecord(record: RecordRef) {
        for (attachment in metadata.findAttachmentsForRecord(record)) {
            detach(attachment, purgeBlob = true)
        }
    }
```

- [ ] **Step 4: GREEN を確認**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt \
        core/src/test/kotlin/net/brightroom/aktivestorage/PurgeRecordTest.kt
git commit -m "$(cat <<'EOF'
feat: add purgeRecord for record-scoped bulk purge

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: E2E テスト + ABI ベースライン再生成 + 全体検証

**Files:**
- Modify: `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt`
- Modify: `core/api/core.api`, `metadata-exposed-jdbc/api/metadata-exposed-jdbc.api`（`apiDump` で再生成）

- [ ] **Step 1: E2E テストを書く**

`EndToEndIT.kt` に追加。import に `kotlin.test.assertNull` / `kotlin.test.assertTrue` / `kotlin.time.Clock` を追加する。`storage` は PER_CLASS で共有なので、レコード ID を他テストと衝突しない値（`del` / `bulk`）にする。孤立 Blob の存在は `AktiveStorage` に id 直引きの公開メソッドが無いため、`reclaimUnattached` の戻り値で間接検証する。

```kotlin
    @Test
    fun `detach purges blob and object, and reclaim sweeps a kept-but-orphaned blob`() =
        runBlocking {
            val record = RecordRef("User", "del")
            val att = storage.attach(record, "avatar", ContentSource.ofBytes("a.png", "image/png", "bytes".encodeToByteArray()))

            // purge=true で実体と Blob 行が消える（blobOf が null になる）
            storage.detach(att, purgeBlob = true)
            assertNull(storage.blobOf(att))

            // purge=false で残した Blob は孤立として reclaim 対象になる
            val att2 = storage.attach(record, "cover", ContentSource.ofBytes("c.png", "image/png", "more".encodeToByteArray()))
            storage.detach(att2, purgeBlob = false)

            // 作成済み Blob は猶予 now で対象。少なくとも att2 由来の 1 件が回収される
            val reclaimed = storage.reclaimUnattached(Clock.System.now())
            assertTrue(reclaimed >= 1)
        }

    @Test
    fun `purgeRecord removes all attachments of a record`() =
        runBlocking {
            val record = RecordRef("User", "bulk")
            storage.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            storage.attach(record, "documents", ContentSource.ofBytes("d", "text/plain", "y".encodeToByteArray()))

            storage.purgeRecord(record)

            assertEquals(0, storage.attachments(record, "avatar").size)
            assertEquals(0, storage.attachments(record, "documents").size)
        }
```

注: 既存の E2E テスト（redirect / large payload）は Blob を attach したまま残すため、`reclaimUnattached` の対象にならず影響を受けない。`assertEquals` の import は既存の `assertContentEquals` と同様に追加する。

- [ ] **Step 2: E2E を実行して緑を確認**

Run: `./gradlew :integration-tests:integrationTest`
Expected: PASS（Docker 必須。MinIO / PostgreSQL コンテナが起動する）

- [ ] **Step 3: ABI ベースラインを再生成**

Run: `./gradlew apiDump`
Expected: `core/api/core.api` に `countAttachmentsForBlob` / `findUnattachedBlobs` / `findAttachmentsForRecord` / `reclaimUnattached` / `purgeRecord` が追加され、`metadata-exposed-jdbc/api/metadata-exposed-jdbc.api` も更新される。

- [ ] **Step 4: 全体検証**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL（`apiCheck` が再生成済みベースラインと一致して緑。spotless / 全 `test` / `integrationTest` も緑）

- [ ] **Step 5: Commit**

```bash
git add integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt \
        core/api/core.api \
        metadata-exposed-jdbc/api/metadata-exposed-jdbc.api
git commit -m "$(cat <<'EOF'
test: cover deletion lifecycle end to end; refresh ABI baselines

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## 完了時の状態

- `detach(purgeBlob=true)` は最後の参照を外したときだけ実体＋Blob 行を削除する。
- `reclaimUnattached(olderThan)` が猶予付きで孤立 Blob を掃除し、再実行で収束する。
- `purgeRecord(record)` がレコードの全添付を参照カウント安全に一括 purge する。
- `MetadataStore` ポートは 3 操作（count / unattached / forRecord）を備え、§14 のポート粒度が確定する。
- ABI ベースラインが更新され、`check` が緑。

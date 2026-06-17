package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            // 境界値（createdAt == olderThan=1000 → 厳密 < なので対象外）
            m.insertBlob(blob("edge", "k-edge", 1000))
            s.objects["k-edge"] = "e".encodeToByteArray()
            // 紐付き（対象外）
            m.insertBlob(blob("att", "k-att", 100))
            s.objects["k-att"] = "z".encodeToByteArray()
            m.insertAttachment(
                Attachment(AttachmentId("a"), "avatar", RecordRef("User", "u"), BlobId("att"), Instant.fromEpochMilliseconds(100)),
            )

            val reclaimed = st.reclaimUnattached(Instant.fromEpochMilliseconds(1000))

            assertEquals(1, reclaimed)
            assertFalse(s.exists("k-old"))
            assertNull(m.findBlob(BlobId("old")))
            assertTrue(s.exists("k-new"))
            assertNotNull(m.findBlob(BlobId("new")))
            assertTrue(s.exists("k-edge"))
            assertNotNull(m.findBlob(BlobId("edge")))
            assertTrue(s.exists("k-att"))
            assertNotNull(m.findBlob(BlobId("att")))
        }

    @Test
    fun `reclaimUnattached skips blobs owned by another service`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService() // name = "memory"
            val st = sut(s, m)

            // このサービス所有の孤立（対象）
            m.insertBlob(Blob(BlobId("mine"), "k-mine", "f", "image/png", 1, "c", "memory", Instant.fromEpochMilliseconds(100)))
            s.objects["k-mine"] = "x".encodeToByteArray()
            // 別サービス所有の孤立（対象外。実体はこの service には無い）
            m.insertBlob(Blob(BlobId("theirs"), "k-theirs", "f", "image/png", 1, "c", "other", Instant.fromEpochMilliseconds(100)))
            s.objects["k-theirs"] = "y".encodeToByteArray()

            val reclaimed = st.reclaimUnattached(Instant.fromEpochMilliseconds(1000))

            assertEquals(1, reclaimed)
            assertNull(m.findBlob(BlobId("mine"))) // 自サービス分は回収
            assertNotNull(m.findBlob(BlobId("theirs"))) // 別サービス分は残る
            assertTrue(s.exists("k-theirs"))
        }
}

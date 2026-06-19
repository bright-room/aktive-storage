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

class DetachRefCountTest {
    private val record = RecordRef("User", "42")

    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner(ByteArray(32) { 1 }))

    @Test
    fun `detach keeps blob while another attachment still references it`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val att1 = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val blob = m.findBlob(att1.blobId)!!
            val att2 = Attachment(AttachmentId("att2"), "cover", record, blob.id, Instant.fromEpochMilliseconds(0))
            m.insertAttachment(att2)

            st.detach(att1, purgeBlob = true)

            assertNotNull(m.findBlob(blob.id))
            assertTrue(s.exists(blob.key))
        }

    @Test
    fun `detach skips purge for a blob owned by another service`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService() // name = "memory"
            val st = sut(s, m)
            // 別サービス("other")所有の Blob とその実体・関連を用意
            val blob = Blob(BlobId("foreign"), "k-foreign", "f.png", "image/png", 1, "c", "other", Instant.fromEpochMilliseconds(0))
            m.insertBlob(blob)
            s.objects["k-foreign"] = "x".encodeToByteArray()
            val att = Attachment(AttachmentId("af"), "avatar", record, blob.id, Instant.fromEpochMilliseconds(0))
            m.insertAttachment(att)

            st.detach(att, purgeBlob = true)

            // 関連は外れるが、他サービス所有の Blob 行・実体は残る
            assertEquals(0, m.attachments.size)
            assertNotNull(m.findBlob(blob.id))
            assertTrue(s.exists("k-foreign"))
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

            assertNull(m.findBlob(blob.id))
            assertFalse(s.exists(blob.key))
        }
}

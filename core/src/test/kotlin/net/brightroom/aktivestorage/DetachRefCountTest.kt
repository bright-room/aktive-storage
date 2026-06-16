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
            val att2 = Attachment(AttachmentId("att2"), "cover", record, blob.id, Instant.fromEpochMilliseconds(0))
            m.insertAttachment(att2)

            st.detach(att1, purgeBlob = true)

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

            assertNull(m.findBlob(blob.id))
            assertFalse(s.exists(blob.key))
        }
}

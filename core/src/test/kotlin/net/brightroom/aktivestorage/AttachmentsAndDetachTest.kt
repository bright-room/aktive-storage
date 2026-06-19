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

class AttachmentsAndDetachTest {
    private val record = RecordRef("User", "42")

    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner(ByteArray(32) { 1 }))

    @Test
    fun `attachments lists by record and name`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            assertEquals(1, st.attachments(record, "avatar").size)
            assertEquals(0, st.attachments(record, "documents").size)
        }

    @Test
    fun `detach with purge removes attachment, blob and object`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val blob = m.findBlob(att.blobId)!!

            st.detach(att, purgeBlob = true)

            assertEquals(0, m.attachments.size)
            assertNull(m.findBlob(att.blobId))
            assertFalse(s.objects.containsKey(blob.key))
        }

    @Test
    fun `detach without purge keeps blob and object`() =
        runTest {
            val m = InMemoryMetadataStore()
            val s = InMemoryStorageService()
            val st = sut(s, m)
            val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val blob = m.findBlob(att.blobId)!!

            st.detach(att, purgeBlob = false)

            assertEquals(0, m.attachments.size)
            assertNotNull(m.findBlob(att.blobId))
            assertTrue(s.objects.containsKey(blob.key))
        }
}

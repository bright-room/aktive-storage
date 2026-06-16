package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
            st.attach(RecordRef("User", "99"), "avatar", ContentSource.ofBytes("o", "text/plain", "z".encodeToByteArray()))
            val avatarKey = m.findBlob(avatar.blobId)!!.key
            val docKey = m.findBlob(doc.blobId)!!.key

            st.purgeRecord(record)

            assertEquals(0, st.attachments(record, "avatar").size)
            assertEquals(0, st.attachments(record, "documents").size)
            assertFalse(s.exists(avatarKey))
            assertFalse(s.exists(docKey))
            assertNull(m.findBlob(avatar.blobId))
            assertNull(m.findBlob(doc.blobId))
            // 別レコードは無傷
            assertEquals(1, st.attachments(RecordRef("User", "99"), "avatar").size)
        }
}

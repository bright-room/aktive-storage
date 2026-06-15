package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals

class BlobOfTest {
    @Test
    fun `blobOf returns the blob for an attachment`() =
        runTest {
            val m = InMemoryMetadataStore()
            val st = AktiveStorage(InMemoryStorageService(), m, HmacReferenceSigner("k".encodeToByteArray()))
            val att = st.attach(RecordRef("U", "1"), "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            assertEquals(att.blobId, st.blobOf(att)!!.id)
        }
}

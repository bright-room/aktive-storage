package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AttachTest {
    private fun storage(
        service: InMemoryStorageService = InMemoryStorageService(),
        metadata: InMemoryMetadataStore = InMemoryMetadataStore(),
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner(ByteArray(32) { 1 }),
    )

    @Test
    fun `attach stores bytes and persists blob and attachment`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)

            val att =
                sut.attach(
                    record = RecordRef("User", "42"),
                    name = "avatar",
                    content = ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()),
                )

            val blob = metadata.findBlob(att.blobId)!!
            assertEquals("a.png", blob.filename)
            assertEquals("image/png", blob.contentType)
            assertEquals(3L, blob.byteSize)
            assertEquals("memory", blob.serviceName)
            assertEquals(1, metadata.attachments.size)
            assertContentEquals("PNG".encodeToByteArray(), service.objects.getValue(blob.key))
        }

    @Test
    fun `attach computes base64 md5 checksum`() =
        runTest {
            val metadata = InMemoryMetadataStore()
            val sut = storage(metadata = metadata)
            val att =
                sut.attach(
                    RecordRef("U", "1"),
                    "f",
                    ContentSource.ofBytes("a", "text/plain", "abc".encodeToByteArray()),
                )
            // base64(md5("abc")) == "kAFQmDzST7DWlj99KOF/cg=="
            assertEquals("kAFQmDzST7DWlj99KOF/cg==", metadata.findBlob(att.blobId)!!.checksum)
        }
}

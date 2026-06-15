package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DomainTypesTest {
    @Test
    fun `blob holds key and metadata distinctly from id`() {
        val blob =
            Blob(
                id = BlobId("b1"),
                key = "token-abc",
                filename = "a.png",
                contentType = "image/png",
                byteSize = 3,
                checksum = "chk",
                serviceName = "fs",
                createdAt = Instant.fromEpochMilliseconds(0),
            )
        assertEquals(BlobId("b1"), blob.id)
        assertEquals("token-abc", blob.key)
    }

    @Test
    fun `attachment references a record and a blob`() {
        val att =
            Attachment(
                id = AttachmentId("a1"),
                name = "avatar",
                record = RecordRef("User", "42"),
                blobId = BlobId("b1"),
                createdAt = Instant.fromEpochMilliseconds(0),
            )
        assertEquals(RecordRef("User", "42"), att.record)
        assertEquals(BlobId("b1"), att.blobId)
    }
}

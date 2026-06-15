package net.brightroom.aktivestorage.fakes

import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef

class InMemoryMetadataStore : MetadataStore {
    val blobs = mutableMapOf<String, Blob>()
    val attachments = mutableMapOf<String, Attachment>()

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
}

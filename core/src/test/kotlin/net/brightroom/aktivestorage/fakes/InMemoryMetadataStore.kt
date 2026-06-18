package net.brightroom.aktivestorage.fakes

import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef
import kotlin.time.Instant

class InMemoryMetadataStore : MetadataStore {
    val blobs = mutableMapOf<String, Blob>()
    val attachments = mutableMapOf<String, Attachment>()

    // key = "originId|digest" -> variantBlobId
    val variants = mutableMapOf<String, String>()

    private fun variantKey(
        originId: String,
        digest: String,
    ) = "$originId|$digest"

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

    override suspend fun countAttachmentsForBlob(blobId: BlobId): Int = attachments.values.count { it.blobId == blobId }

    override suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob> {
        val variantBlobIds = variants.values.toSet()
        return blobs.values.filter { blob ->
            blob.createdAt < olderThan &&
                attachments.values.none { it.blobId == blob.id } &&
                blob.id.value !in variantBlobIds
        }
    }

    override suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment> = attachments.values.filter { it.record == record }

    override suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob? = variants[variantKey(originBlobId.value, variationDigest)]?.let { blobs[it] }

    override suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    ) {
        blobs[variant.id.value] = variant
        variants[variantKey(originBlobId.value, variationDigest)] = variant.id.value
    }

    override suspend fun findVariantsOf(originBlobId: BlobId): List<Blob> {
        val prefix = "${originBlobId.value}|"
        return variants.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { blobs[it.value] }
    }

    override suspend fun deleteVariantsOf(originBlobId: BlobId) {
        val prefix = "${originBlobId.value}|"
        val matching = variants.entries.filter { it.key.startsWith(prefix) }
        for (e in matching) {
            blobs.remove(e.value)
            variants.remove(e.key)
        }
    }
}

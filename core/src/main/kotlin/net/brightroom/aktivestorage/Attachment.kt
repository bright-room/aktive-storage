package net.brightroom.aktivestorage

import kotlin.time.Instant

public data class Attachment(
    public val id: AttachmentId,
    public val name: String,
    public val record: RecordRef,
    public val blobId: BlobId,
    public val createdAt: Instant,
)

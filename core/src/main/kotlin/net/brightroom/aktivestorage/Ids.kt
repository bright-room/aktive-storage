package net.brightroom.aktivestorage

@JvmInline
public value class BlobId(
    public val value: String,
)

@JvmInline
public value class AttachmentId(
    public val value: String,
)

@JvmInline
public value class PresignedUrl(
    public val value: String,
)

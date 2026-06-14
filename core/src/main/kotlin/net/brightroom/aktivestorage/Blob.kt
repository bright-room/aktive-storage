package net.brightroom.aktivestorage

import kotlin.time.Instant

public data class Blob(
    public val id: BlobId,
    public val key: String,
    public val filename: String,
    public val contentType: String,
    public val byteSize: Long,
    public val checksum: String,
    public val serviceName: String,
    public val createdAt: Instant,
)

package net.brightroom.aktivestorage.metadata.exposed

import org.jetbrains.exposed.v1.core.Table

// created_at は epoch millis（BIGINT）として保持し、kotlin.time.Instant と相互変換する。
internal object BlobsTable : Table("aktive_blobs") {
    val id = varchar("id", 64)
    val key = varchar("key", 512).uniqueIndex()
    val filename = varchar("filename", 1024)
    val contentType = varchar("content_type", 255)
    val byteSize = long("byte_size")
    val checksum = varchar("checksum", 128)
    val serviceName = varchar("service_name", 64)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object AttachmentsTable : Table("aktive_attachments") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)
    val recordType = varchar("record_type", 255)
    val recordId = varchar("record_id", 255)
    val blobId = varchar("blob_id", 64).references(BlobsTable.id)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, recordType, recordId, name)
    }
}

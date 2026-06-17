package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant

public class ExposedMetadataStore(
    private val db: Database,
) : MetadataStore {
    /** テスト/開発用のスキーマ作成。本番は各自のマイグレーションで管理する。 */
    public fun createSchema() {
        transaction(db) { SchemaUtils.create(BlobsTable, AttachmentsTable) }
    }

    override suspend fun insertBlob(blob: Blob): Unit =
        dbQuery {
            BlobsTable.insert {
                it[id] = blob.id.value
                it[key] = blob.key
                it[filename] = blob.filename
                it[contentType] = blob.contentType
                it[byteSize] = blob.byteSize
                it[checksum] = blob.checksum
                it[serviceName] = blob.serviceName
                it[createdAt] = blob.createdAt.toEpochMilliseconds()
            }
            Unit
        }

    override suspend fun findBlob(id: BlobId): Blob? =
        dbQuery {
            BlobsTable
                .selectAll()
                .where { BlobsTable.id eq id.value }
                .singleOrNull()
                ?.toBlob()
        }

    override suspend fun deleteBlob(id: BlobId): Unit =
        dbQuery {
            BlobsTable.deleteWhere { BlobsTable.id eq id.value }
            Unit
        }

    override suspend fun insertAttachment(attachment: Attachment): Unit =
        dbQuery {
            AttachmentsTable.insert {
                it[id] = attachment.id.value
                it[name] = attachment.name
                it[recordType] = attachment.record.type
                it[recordId] = attachment.record.id
                it[blobId] = attachment.blobId.value
                it[createdAt] = attachment.createdAt.toEpochMilliseconds()
            }
            Unit
        }

    override suspend fun findAttachments(
        record: RecordRef,
        name: String,
    ): List<Attachment> =
        dbQuery {
            AttachmentsTable
                .selectAll()
                .where {
                    (AttachmentsTable.recordType eq record.type) and
                        (AttachmentsTable.recordId eq record.id) and
                        (AttachmentsTable.name eq name)
                }.map { it.toAttachment() }
        }

    override suspend fun deleteAttachment(id: AttachmentId): Unit =
        dbQuery {
            AttachmentsTable.deleteWhere { AttachmentsTable.id eq id.value }
            Unit
        }

    override suspend fun countAttachmentsForBlob(blobId: BlobId): Int =
        dbQuery {
            AttachmentsTable
                .selectAll()
                .where { AttachmentsTable.blobId eq blobId.value }
                .count()
                .toInt()
        }

    override suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob> =
        dbQuery {
            val cutoff = olderThan.toEpochMilliseconds()
            (BlobsTable leftJoin AttachmentsTable)
                .selectAll()
                .where { AttachmentsTable.id.isNull() and (BlobsTable.createdAt less cutoff) }
                .map { it.toBlob() }
        }

    override suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment> =
        dbQuery {
            AttachmentsTable
                .selectAll()
                .where {
                    (AttachmentsTable.recordType eq record.type) and
                        (AttachmentsTable.recordId eq record.id)
                }.map { it.toAttachment() }
        }

    private suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) { transaction(db) { block() } }

    private fun ResultRow.toBlob() =
        Blob(
            id = BlobId(this[BlobsTable.id]),
            key = this[BlobsTable.key],
            filename = this[BlobsTable.filename],
            contentType = this[BlobsTable.contentType],
            byteSize = this[BlobsTable.byteSize],
            checksum = this[BlobsTable.checksum],
            serviceName = this[BlobsTable.serviceName],
            createdAt = Instant.fromEpochMilliseconds(this[BlobsTable.createdAt]),
        )

    private fun ResultRow.toAttachment() =
        Attachment(
            id = AttachmentId(this[AttachmentsTable.id]),
            name = this[AttachmentsTable.name],
            record = RecordRef(this[AttachmentsTable.recordType], this[AttachmentsTable.recordId]),
            blobId = BlobId(this[AttachmentsTable.blobId]),
            createdAt = Instant.fromEpochMilliseconds(this[AttachmentsTable.createdAt]),
        )
}

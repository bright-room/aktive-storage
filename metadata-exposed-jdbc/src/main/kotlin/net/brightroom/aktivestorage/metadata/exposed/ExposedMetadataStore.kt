package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.DuplicateVariantException
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
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
        transaction(db) { SchemaUtils.create(BlobsTable, AttachmentsTable, VariantRecordsTable) }
    }

    private fun validateColumns(blob: Blob) {
        require(blob.id.value.length <= 64) { "blob id exceeds 64 chars: ${blob.id.value.length}" }
        require(blob.key.length <= 512) { "storage key exceeds 512 chars: ${blob.key.length}" }
        require(blob.contentType.length <= 255) { "contentType exceeds 255 chars: ${blob.contentType.length}" }
        require(blob.checksum.length <= 128) { "checksum exceeds 128 chars: ${blob.checksum.length}" }
        require(blob.serviceName.length <= 64) { "serviceName exceeds 64 chars: ${blob.serviceName.length}" }
    }

    override suspend fun insertBlob(blob: Blob) {
        validateColumns(blob)
        return dbQuery {
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
            // 派生（variant）は VariantRecordsTable の二つの FK で曖昧になるため、
            // variant_blob_id に対する明示 LEFT JOIN で DB 側除外する。
            (BlobsTable leftJoin AttachmentsTable)
                .join(VariantRecordsTable, JoinType.LEFT, onColumn = BlobsTable.id, otherColumn = VariantRecordsTable.variantBlobId)
                .selectAll()
                .where {
                    AttachmentsTable.id.isNull() and
                        VariantRecordsTable.variantBlobId.isNull() and
                        (BlobsTable.createdAt less cutoff)
                }.map { it.toBlob() }
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

    override suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob? =
        dbQuery {
            BlobsTable
                .join(VariantRecordsTable, JoinType.INNER, onColumn = BlobsTable.id, otherColumn = VariantRecordsTable.variantBlobId)
                .selectAll()
                .where {
                    (VariantRecordsTable.originBlobId eq originBlobId.value) and
                        (VariantRecordsTable.variationDigest eq variationDigest)
                }.singleOrNull()
                ?.toBlob()
        }

    override suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    ) {
        validateColumns(variant)
        try {
            dbQuery {
                BlobsTable.insert {
                    it[id] = variant.id.value
                    it[key] = variant.key
                    it[filename] = variant.filename
                    it[contentType] = variant.contentType
                    it[byteSize] = variant.byteSize
                    it[checksum] = variant.checksum
                    it[serviceName] = variant.serviceName
                    it[createdAt] = variant.createdAt.toEpochMilliseconds()
                }
                VariantRecordsTable.insert {
                    it[VariantRecordsTable.originBlobId] = originBlobId.value
                    it[VariantRecordsTable.variationDigest] = variationDigest
                    it[variantBlobId] = variant.id.value
                }
                Unit
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState?.startsWith("23") == true) {
                throw DuplicateVariantException("variant ($originBlobId, $variationDigest) already exists", e)
            }
            throw e
        }
    }

    override suspend fun findVariantsOf(originBlobId: BlobId): List<Blob> =
        dbQuery {
            BlobsTable
                .join(VariantRecordsTable, JoinType.INNER, onColumn = BlobsTable.id, otherColumn = VariantRecordsTable.variantBlobId)
                .selectAll()
                .where { VariantRecordsTable.originBlobId eq originBlobId.value }
                .map { it.toBlob() }
        }

    override suspend fun isVariantBlob(blobId: BlobId): Boolean =
        dbQuery {
            VariantRecordsTable
                .selectAll()
                .where { VariantRecordsTable.variantBlobId eq blobId.value }
                .limit(1)
                .any()
        }

    override suspend fun deleteVariantsOf(originBlobId: BlobId): Unit =
        dbQuery {
            val variantIds =
                VariantRecordsTable
                    .selectAll()
                    .where { VariantRecordsTable.originBlobId eq originBlobId.value }
                    .map { it[VariantRecordsTable.variantBlobId] }
            VariantRecordsTable.deleteWhere { VariantRecordsTable.originBlobId eq originBlobId.value }
            if (variantIds.isNotEmpty()) {
                BlobsTable.deleteWhere { BlobsTable.id inList variantIds }
            }
            Unit
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

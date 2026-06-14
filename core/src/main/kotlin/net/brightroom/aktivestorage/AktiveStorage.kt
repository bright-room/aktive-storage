package net.brightroom.aktivestorage

import java.util.UUID
import kotlin.time.Clock

public class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val clock: Clock = Clock.System,
) {
    /** 添付を作成する。順序: スプール→Blob行→実体put→Attachment行。 */
    public suspend fun attach(
        record: RecordRef,
        name: String,
        content: ContentSource,
    ): Attachment {
        val spooled = spool(content)
        try {
            val key = keyGenerator.generate(KeyContext(spooled.filename, spooled.contentType, record))
            val blob =
                Blob(
                    id = BlobId(UUID.randomUUID().toString()),
                    key = key,
                    filename = spooled.filename,
                    contentType = spooled.contentType,
                    byteSize = spooled.byteSize,
                    checksum = spooled.checksumBase64,
                    serviceName = service.name,
                    createdAt = clock.now(),
                )
            metadata.insertBlob(blob)
            service.put(key, spooled, ObjectMetadata(blob.contentType, blob.byteSize, blob.checksum))
            val attachment =
                Attachment(
                    id = AttachmentId(UUID.randomUUID().toString()),
                    name = name,
                    record = record,
                    blobId = blob.id,
                    createdAt = clock.now(),
                )
            metadata.insertAttachment(attachment)
            return attachment
        } finally {
            spooled.cleanup()
        }
    }

    public suspend fun attachments(
        record: RecordRef,
        name: String,
    ): List<Attachment> = metadata.findAttachments(record, name)

    /**
     * 添付を外す。purgeBlob=true で Blob 行と実体も削除する。
     * 注: MVP は参照カウントしない（共有 Blob の安全な回収はフェーズ2）。
     */
    public suspend fun detach(
        attachment: Attachment,
        purgeBlob: Boolean = true,
    ) {
        metadata.deleteAttachment(attachment.id)
        if (!purgeBlob) return
        val blob = metadata.findBlob(attachment.blobId) ?: return
        metadata.deleteBlob(blob.id)
        service.delete(blob.key)
    }
}

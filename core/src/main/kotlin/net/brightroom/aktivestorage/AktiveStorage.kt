package net.brightroom.aktivestorage

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
            try {
                service.put(key, spooled, ObjectMetadata(blob.contentType, blob.byteSize, blob.checksum))
            } catch (e: Exception) {
                metadata.deleteBlob(blob.id)
                throw e
            }
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

    /** 添付に対応する Blob を引く。 */
    public suspend fun blobOf(attachment: Attachment): Blob? = metadata.findBlob(attachment.blobId)

    /**
     * 添付を外す。purgeBlob=true でも、その Blob を参照する他の Attachment が
     * 残っている場合は Blob 行・実体を残す（参照カウント安全）。
     * 実体 → Blob 行 の順で削除し、冪等 delete 前提で再実行可能にする。
     * purge は自サービス（`service.name`）所有の Blob のみ。他サービス所有の
     * Blob は実体・行を残す（所有サービスの `reclaimUnattached` が回収する）。
     */
    public suspend fun detach(
        attachment: Attachment,
        purgeBlob: Boolean = true,
    ) {
        metadata.deleteAttachment(attachment.id)
        if (!purgeBlob) return
        if (metadata.countAttachmentsForBlob(attachment.blobId) > 0) return
        val blob = metadata.findBlob(attachment.blobId) ?: return
        if (blob.serviceName != service.name) return
        service.delete(blob.key)
        metadata.deleteBlob(blob.id)
    }

    /**
     * 参照ゼロ かつ olderThan より前に作られた Blob を回収し、回収できた件数を返す。
     * olderThan は呼び出し側が `now - grace` として渡し、進行中の attach を除外する。
     * 実体 → Blob 行 の順で削除する。途中失敗時は例外を伝播し、削除済み分は確定する
     * （冪等 delete 前提で再実行すれば残りを処理して収束する）。
     * いつ走らせるかは利用者の責務（このライブラリは job を持たない）。
     * このサービス（`service.name`）が所有する Blob のみを対象とする（共有 MetadataStore で他サービスの Blob を消さない）。
     */
    public suspend fun reclaimUnattached(olderThan: Instant): Int {
        val orphans = metadata.findUnattachedBlobs(olderThan)
        var reclaimed = 0
        for (blob in orphans) {
            if (blob.serviceName != service.name) continue
            service.delete(blob.key)
            metadata.deleteBlob(blob.id)
            reclaimed++
        }
        return reclaimed
    }

    /** レコードの全添付（name 問わず）を参照カウント安全に detach+purge する。 */
    public suspend fun purgeRecord(record: RecordRef) {
        for (attachment in metadata.findAttachmentsForRecord(record)) {
            detach(attachment, purgeBlob = true)
        }
    }

    /** 配信用の署名参照トークンを発行する。 */
    public fun signedReference(
        blob: Blob,
        ttl: Duration,
    ): String = signer.sign(blob.id, clock.now() + ttl)

    /**
     * トークンを検証し配信方法を返す。
     * presigned 対応サービスは Redirect、非対応(fs)は Proxy へ自動フォールバック。
     */
    public suspend fun resolveForDelivery(
        token: String,
        redirectTtl: Duration = 30.seconds,
    ): Delivery? {
        val blobId = signer.verify(token) ?: return null
        val blob = metadata.findBlob(blobId) ?: return null
        val url = service.presignedGetUrl(blob.key, redirectTtl)
        return if (url != null) Delivery.Redirect(url) else Delivery.Proxy(blob, service.get(blob.key))
    }
}

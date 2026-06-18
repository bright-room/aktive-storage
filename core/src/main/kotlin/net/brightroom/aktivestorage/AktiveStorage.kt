package net.brightroom.aktivestorage

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
public class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val checksum: Checksum = Md5Checksum(),
    private val variantProcessor: VariantProcessor? = null,
    private val clock: Clock = Clock.System,
) {
    /** 添付を作成する。順序: スプール→Blob行→実体put→Attachment行。 */
    public suspend fun attach(
        record: RecordRef,
        name: String,
        content: ContentSource,
    ): Attachment {
        val spooled = spool(content, checksum)
        try {
            val key = keyGenerator.generate(KeyContext(spooled.filename, spooled.contentType, record))
            val blob =
                Blob(
                    id = BlobId(Uuid.random().toString()),
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
                    id = AttachmentId(Uuid.random().toString()),
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
     * blob に variation を適用した派生 Blob を返す（遅延生成）。
     * 既存の variant 記録があればそれを返し、無ければ生成→実体保存→記録して返す。
     * 戻りは通常の Blob で、既存の署名参照/配信経路にそのまま乗る。
     * variantProcessor 未注入時は IllegalStateException。
     *
     * 派生は生成元（このインスタンス）の `service` に保存され、purge/reclaim も同 `service` で行う。
     * よって variant は **元 Blob を所有するサービス上で生成すること**。所有が一致しない場合は
     * 取得失敗・誤サービス書き込み・削除取り残しを避けるため即座に [IllegalStateException]。
     *
     * 遅延の初回生成は競合しうる（同一 (blob, variation) の同時要求が両方生成に進む）。派生キーは
     * 決定的なため実体は同一内容で上書きされ無害で、記録挿入が一意制約で失敗した場合は既存記録を
     * 引いて返す（収束する）。いつ・どの並行度で呼ぶかは利用者の責務。
     */
    public suspend fun variant(
        blob: Blob,
        variation: Variation,
    ): Blob {
        val processor =
            variantProcessor
                ?: error("variant() requires a VariantProcessor; none was injected")
        check(blob.serviceName == service.name) {
            "variant() must run on the owning service: blob=${blob.serviceName}, current=${service.name}"
        }
        val digest = digestOf(variation)
        metadata.findVariant(blob.id, digest)?.let { return it }

        val originBytes = service.get(blob.key).buffered().use { it.readByteArray() }
        val origin = ContentSource.ofBytes(blob.filename, blob.contentType, originBytes)
        val processed = processor.process(origin, variation)

        val spooled = spool(processed, checksum)
        try {
            val key = "${blob.key}/variants/$digest"
            val variantBlob =
                Blob(
                    id = BlobId(Uuid.random().toString()),
                    key = key,
                    filename = spooled.filename,
                    contentType = spooled.contentType,
                    byteSize = spooled.byteSize,
                    checksum = spooled.checksumBase64,
                    serviceName = service.name,
                    createdAt = clock.now(),
                )
            service.put(key, spooled, ObjectMetadata(variantBlob.contentType, variantBlob.byteSize, variantBlob.checksum))
            return try {
                metadata.insertVariant(blob.id, digest, variantBlob)
                variantBlob
            } catch (e: Exception) {
                // 並行初回生成の競合: 既に記録された派生があればそれを返して収束する。
                metadata.findVariant(blob.id, digest) ?: throw e
            }
        } finally {
            spooled.cleanup()
        }
    }

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
        purgeVariantsOf(blob)
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
            purgeVariantsOf(blob)
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

    /** 元 Blob に紐づく派生の実体を消し、variant 記録と派生 Blob 行を削除する。 */
    private suspend fun purgeVariantsOf(origin: Blob) {
        for (variant in metadata.findVariantsOf(origin.id)) {
            service.delete(variant.key)
        }
        metadata.deleteVariantsOf(origin.id)
    }

    private fun digestOf(variation: Variation): String =
        Base64.UrlSafe
            .withPadding(Base64.PaddingOption.ABSENT)
            .encode(checksum.newHasher().also { it.update(variation.canonicalForm.encodeToByteArray()) }.digest())
}

package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlin.time.Duration
import kotlin.time.Instant

/** 保存先抽象。キーは生成も導出もしない dumb な口。 */
public interface StorageService {
    public val name: String

    public suspend fun put(
        key: String,
        content: ContentSource,
        meta: ObjectMetadata,
    )

    /** 実体を読み出す。返した [RawSource] は呼び出し側が close すること（S3 は temp ファイル裏付けで、close 時に削除する）。 */
    public suspend fun get(key: String): RawSource

    public suspend fun exists(key: String): Boolean

    /** キーに対応する実体を削除する。存在しないキーに対しては no-op（冪等）。 */
    public suspend fun delete(key: String)

    /** presigned GET URL。非対応（fs 等）は null。 */
    public suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): PresignedUrl?
}

/** メタデータ永続化（最小操作）。 */
public interface MetadataStore {
    public suspend fun insertBlob(blob: Blob)

    public suspend fun findBlob(id: BlobId): Blob?

    public suspend fun deleteBlob(id: BlobId)

    public suspend fun insertAttachment(attachment: Attachment)

    public suspend fun findAttachments(
        record: RecordRef,
        name: String,
    ): List<Attachment>

    public suspend fun deleteAttachment(id: AttachmentId)
}

/** ストレージキー生成ストラテジ。 */
public fun interface KeyGenerator {
    public fun generate(context: KeyContext): String
}

/** 配信参照の署名・検証。 */
public interface ReferenceSigner {
    public fun sign(
        blobId: BlobId,
        expiresAt: Instant,
    ): String

    public fun verify(token: String): BlobId?
}

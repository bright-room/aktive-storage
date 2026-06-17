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

    /** ある Blob を参照する Attachment 数。参照カウント安全 purge と孤立判定の基盤。 */
    public suspend fun countAttachmentsForBlob(blobId: BlobId): Int

    /** 参照ゼロ かつ createdAt < olderThan の Blob。派生（variant）Blob は対象外。olderThan の猶予で進行中 attach を除外する。 */
    public suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob>

    /** name を問わずレコードの全添付。レコード削除との連動（一括 purge）に使う。 */
    public suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment>

    /** (元 Blob, variation digest) に対応する派生 Blob。無ければ null。 */
    public suspend fun findVariant(
        originBlobId: BlobId,
        variationDigest: String,
    ): Blob?

    /** 派生 Blob 行と variant 記録を 1 トランザクションで挿入する。 */
    public suspend fun insertVariant(
        originBlobId: BlobId,
        variationDigest: String,
        variant: Blob,
    )

    /** ある元 Blob に紐づく全派生 Blob。カスケード削除に使う。 */
    public suspend fun findVariantsOf(originBlobId: BlobId): List<Blob>

    /** ある元 Blob の variant 記録と派生 Blob 行をまとめて削除する（実体削除は呼び出し側）。 */
    public suspend fun deleteVariantsOf(originBlobId: BlobId)
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

/** ストリーミング・チェックサム生成。content を一括ロードせず逐次更新できること。 */
public fun interface Checksum {
    public fun newHasher(): Hasher
}

/** 逐次更新できるハッシャ。生バイトの digest を返し、符号化は呼び出し側に委ねる。 */
public interface Hasher {
    public fun update(
        source: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = source.size,
    )

    /**
     * 蓄積したバイト列のダイジェストを返す。一度だけ呼ぶこと。
     * 呼び出し後の状態は実装依存（リセットされる場合がある）。
     */
    public fun digest(): ByteArray
}

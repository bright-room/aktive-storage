package net.brightroom.aktivestorage

/** insertVariant が (originBlobId, variationDigest) の一意制約に衝突したときに投げる。並行初回生成の収束に使う。 */
public class DuplicateVariantException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** variant 元画像が maxVariantSourceBytes を超える場合に投げる。 */
public class VariantSourceTooLargeException(
    public val byteSize: Long,
    public val maxBytes: Long,
) : RuntimeException("variant source $byteSize bytes exceeds limit $maxBytes")

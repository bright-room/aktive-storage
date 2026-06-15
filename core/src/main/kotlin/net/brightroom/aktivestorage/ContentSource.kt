package net.brightroom.aktivestorage

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/** アップロード元バイト列とそのメタ情報。`open()` は毎回新しいストリームを返す。 */
public interface ContentSource {
    public val filename: String
    public val contentType: String

    public fun open(): RawSource

    public companion object {
        public fun ofBytes(
            filename: String,
            contentType: String,
            bytes: ByteArray,
        ): ContentSource = ByteArrayContentSource(filename, contentType, bytes.copyOf())
    }
}

internal class ByteArrayContentSource(
    override val filename: String,
    override val contentType: String,
    private val bytes: ByteArray,
) : ContentSource {
    override fun open(): RawSource = Buffer().also { it.write(bytes) }
}

/** StorageService.put に渡すオブジェクトメタ。 */
public data class ObjectMetadata(
    public val contentType: String,
    public val byteSize: Long,
    public val checksum: String,
)

/** KeyGenerator に渡す生成コンテキスト。 */
public data class KeyContext(
    public val filename: String,
    public val contentType: String,
    public val record: RecordRef,
)

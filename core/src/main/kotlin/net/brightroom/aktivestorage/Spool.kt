package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 一時ファイルへスプールしつつ byteSize と base64(MD5) を確定する。 */
internal class SpooledContent(
    private val tempFile: Path,
    val byteSize: Long,
    val checksumBase64: String,
    override val filename: String,
    override val contentType: String,
) : ContentSource {
    override fun open(): RawSource = SystemFileSystem.source(tempFile)

    fun cleanup() {
        SystemFileSystem.delete(tempFile, mustExist = false)
    }
}

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
internal fun spool(
    content: ContentSource,
    checksum: Checksum,
): SpooledContent {
    val tempFile = Path(SystemTemporaryDirectory, "aktive-${Uuid.random()}.tmp")
    val hasher = checksum.newHasher()
    val chunk = ByteArray(8192)
    var byteSize = 0L
    try {
        content.open().buffered().use { src ->
            SystemFileSystem.sink(tempFile).buffered().use { sink ->
                while (true) {
                    val n = src.readAtMostTo(chunk, 0, chunk.size)
                    if (n == -1) break
                    hasher.update(chunk, 0, n)
                    sink.write(chunk, 0, n)
                    byteSize += n
                }
            }
        }
        val checksumBase64 = Base64.Default.encode(hasher.digest())
        return SpooledContent(tempFile, byteSize, checksumBase64, content.filename, content.contentType)
    } catch (e: Exception) {
        SystemFileSystem.delete(tempFile, mustExist = false)
        throw e
    }
}

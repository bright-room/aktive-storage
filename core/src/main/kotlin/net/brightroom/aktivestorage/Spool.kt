package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

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

internal fun spool(content: ContentSource): SpooledContent {
    val tempFile = Path(SystemTemporaryDirectory, "aktive-${UUID.randomUUID()}.tmp")
    val digest = MessageDigest.getInstance("MD5")
    val chunk = ByteArray(8192)
    var byteSize = 0L
    try {
        content.open().buffered().use { src ->
            SystemFileSystem.sink(tempFile).buffered().use { sink ->
                while (true) {
                    val n = src.readAtMostTo(chunk, 0, chunk.size)
                    if (n == -1) break
                    digest.update(chunk, 0, n)
                    sink.write(chunk, 0, n)
                    byteSize += n
                }
            }
        }
        val checksum = Base64.getEncoder().encodeToString(digest.digest())
        return SpooledContent(tempFile, byteSize, checksum, content.filename, content.contentType)
    } catch (e: Throwable) {
        SystemFileSystem.delete(tempFile, mustExist = false)
        throw e
    }
}

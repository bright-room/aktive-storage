package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
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
    val bytes = content.open().buffered().use { it.readByteArray() }
    digest.update(bytes)
    SystemFileSystem.sink(tempFile).buffered().use { sink -> sink.write(bytes) }
    val checksum = Base64.getEncoder().encodeToString(digest.digest())
    return SpooledContent(tempFile, bytes.size.toLong(), checksum, content.filename, content.contentType)
}

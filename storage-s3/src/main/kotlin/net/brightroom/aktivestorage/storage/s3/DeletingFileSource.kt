package net.brightroom.aktivestorage.storage.s3

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A [RawSource] over a downloaded temp file that deletes the file once the consumer closes it.
 * Used by the S3 adapter so a streamed download does not outlive its backing file.
 */
internal class DeletingFileSource(
    private val path: Path,
) : RawSource {
    private val delegate: RawSource = SystemFileSystem.source(path)

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = delegate.readAtMostTo(sink, byteCount)

    override fun close() {
        try {
            delegate.close()
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}

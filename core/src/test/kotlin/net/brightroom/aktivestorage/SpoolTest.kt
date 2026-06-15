package net.brightroom.aktivestorage

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpoolTest {
    @Test
    fun `spool computes size and base64 md5 and round-trips a multi-MB payload`() {
        val size = 5 * 1024 * 1024 // 5 MiB, far larger than the 8 KiB copy chunk
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val content = ContentSource.ofBytes("big.bin", "application/octet-stream", bytes)

        val spooled = spool(content)
        try {
            assertEquals(size.toLong(), spooled.byteSize)

            val expectedChecksum =
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
            assertEquals(expectedChecksum, spooled.checksumBase64)

            val readBack = spooled.open().buffered().use { it.readByteArray() }
            assertContentEquals(bytes, readBack)
        } finally {
            spooled.cleanup()
        }
    }

    @Test
    fun `spool deletes the temp file when streaming fails`() {
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"))

        fun spoolTemps() =
            tmpDir
                .listFiles { _, n -> n.startsWith("aktive-") && n.endsWith(".tmp") }
                ?.map { it.name }
                ?.toSet() ?: emptySet()

        val before = spoolTemps()

        val failing =
            object : ContentSource {
                override val filename = "boom.bin"
                override val contentType = "application/octet-stream"

                override fun open(): RawSource =
                    object : RawSource {
                        override fun readAtMostTo(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long = throw IOException("boom")

                        override fun close() {}
                    }
            }

        assertFailsWith<IOException> { spool(failing) }
        assertEquals(before, spoolTemps(), "spool must not leave a temp file behind on failure")
    }
}

package net.brightroom.aktivestorage

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}

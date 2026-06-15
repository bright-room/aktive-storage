package net.brightroom.aktivestorage.storage.s3

import aws.smithy.kotlin.runtime.content.toByteArray
import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.ContentSource
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ContentSourceByteStreamTest {
    /** Retry/signing safety: a replayable (isOneShot=false) body must yield the full content on every readFrom. */
    @Test
    fun `readFrom yields a fresh full stream on each call`() =
        runBlocking {
            val bytes = ByteArray(64 * 1024) { (it % 251).toByte() } // > the 8 KiB copy chunk
            val stream =
                ContentSourceByteStream(
                    ContentSource.ofBytes("f.bin", "application/octet-stream", bytes),
                    bytes.size.toLong(),
                )

            assertFalse(stream.isOneShot)
            assertEquals(bytes.size.toLong(), stream.contentLength)

            val first = stream.toByteArray()
            val second = stream.toByteArray()

            assertContentEquals(bytes, first)
            assertContentEquals(bytes, second)
        }
}

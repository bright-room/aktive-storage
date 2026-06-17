package net.brightroom.aktivestorage

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalEncodingApi::class)
class Md5ChecksumTest {
    @Test
    fun `md5 of abc matches known base64 digest`() {
        val hasher = Md5Checksum().newHasher()
        val bytes = "abc".encodeToByteArray()
        hasher.update(bytes)
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", Base64.Default.encode(hasher.digest()))
    }

    @Test
    fun `incremental updates produce the same digest as a single update`() {
        val full = Md5Checksum().newHasher()
        full.update("hello world".encodeToByteArray())

        val chunked = Md5Checksum().newHasher()
        val bytes = "hello world".encodeToByteArray()
        chunked.update(bytes, 0, 5)
        chunked.update(bytes, 5, bytes.size)

        assertEquals(
            Base64.Default.encode(full.digest()),
            Base64.Default.encode(chunked.digest()),
        )
    }

    @Test
    fun `two hashers from the same Checksum instance are independent`() {
        val checksum = Md5Checksum()
        val h1 = checksum.newHasher()
        val h2 = checksum.newHasher()
        h1.update("abc".encodeToByteArray())
        val d1 = Base64.Default.encode(h1.digest())
        val d2 = Base64.Default.encode(h2.digest())
        assertNotEquals(d1, d2)
    }
}

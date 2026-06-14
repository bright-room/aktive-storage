package net.brightroom.aktivestorage

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ContentSourceTest {
    @Test
    fun `byte-backed source re-opens fresh streams`() {
        val src = ContentSource.ofBytes("a.txt", "text/plain", "hello".encodeToByteArray())
        assertEquals("a.txt", src.filename)
        assertEquals("text/plain", src.contentType)
        val first = src.open().use { it.buffered().readByteArray() }
        val second = src.open().use { it.buffered().readByteArray() }
        assertContentEquals("hello".encodeToByteArray(), first)
        assertContentEquals("hello".encodeToByteArray(), second)
    }
}

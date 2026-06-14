package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomTokenKeyGeneratorTest {
    private val ctx = KeyContext("a.png", "image/png", RecordRef("User", "1"))

    @Test
    fun `generates url-safe opaque tokens`() {
        val key = RandomTokenKeyGenerator().generate(ctx)
        assertTrue(key.isNotBlank())
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "url-safe: $key")
    }

    @Test
    fun `generates distinct keys`() {
        val gen = RandomTokenKeyGenerator()
        assertNotEquals(gen.generate(ctx), gen.generate(ctx))
    }
}

package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class VariationTest {
    @Test
    fun `resize rejects non-positive or all-null dimensions`() {
        assertFailsWith<IllegalArgumentException> { Transform.Resize(0, null, ResizeMode.FIT) }
        assertFailsWith<IllegalArgumentException> { Transform.Resize(null, -1, ResizeMode.FIT) }
        assertFailsWith<IllegalArgumentException> { Transform.Resize(null, null, ResizeMode.FIT) }
    }

    @Test
    fun `crop rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> { Transform.Crop(0, 10, Gravity.CENTER) }
        assertFailsWith<IllegalArgumentException> { Transform.Crop(10, -5, Gravity.CENTER) }
    }

    @Test
    fun `convert rejects out-of-range quality`() {
        assertFailsWith<IllegalArgumentException> { Transform.Convert(ImageFormat.JPEG, 101) }
        assertFailsWith<IllegalArgumentException> { Transform.Convert(ImageFormat.WEBP, -1) }
    }

    @Test
    fun `same transforms produce same canonicalForm`() {
        val a = Variation.of(Transform.Resize(100, 100, ResizeMode.FIT), Transform.Grayscale)
        val b = Variation.of(Transform.Resize(100, 100, ResizeMode.FIT), Transform.Grayscale)
        assertEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `different order produces different canonicalForm`() {
        val a = Variation.of(Transform.Grayscale, Transform.Rotate(90))
        val b = Variation.of(Transform.Rotate(90), Transform.Grayscale)
        assertNotEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `different params produce different canonicalForm`() {
        val a = Variation.of(Transform.Convert(ImageFormat.JPEG, 80))
        val b = Variation.of(Transform.Convert(ImageFormat.JPEG, 90))
        assertNotEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `resize with null width encodes underscore`() {
        val v = Variation.of(Transform.Resize(null, 150, ResizeMode.FIT))
        assertEquals("resize:_x150:FIT", v.canonicalForm)
    }

    @Test
    fun `canonicalForm is stable golden value`() {
        val v = Variation.of(Transform.Resize(200, null, ResizeMode.LIMIT), Transform.Convert(ImageFormat.WEBP, null))
        assertEquals("resize:200x_:LIMIT|convert:WEBP:q_", v.canonicalForm)
    }
}

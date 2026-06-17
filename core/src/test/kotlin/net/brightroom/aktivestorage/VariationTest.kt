package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VariationTest {
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
    fun `canonicalForm is stable golden value`() {
        val v = Variation.of(Transform.Resize(200, null, ResizeMode.LIMIT), Transform.Convert(ImageFormat.WEBP, null))
        assertEquals("resize:200x_:LIMIT|convert:WEBP:q_", v.canonicalForm)
    }
}

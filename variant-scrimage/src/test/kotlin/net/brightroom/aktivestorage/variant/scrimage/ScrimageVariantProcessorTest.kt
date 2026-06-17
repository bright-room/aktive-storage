package net.brightroom.aktivestorage.variant.scrimage

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.PngWriter
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Gravity
import net.brightroom.aktivestorage.ImageFormat
import net.brightroom.aktivestorage.ResizeMode
import net.brightroom.aktivestorage.Transform
import net.brightroom.aktivestorage.Variation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrimageVariantProcessorTest {
    private val processor = ScrimageVariantProcessor()

    private fun samplePng(): ByteArray {
        val image = ImmutableImage.create(60, 40)
        return image.bytes(PngWriter.MaxCompression)
    }

    private fun source(bytes: ByteArray) = ContentSource.ofBytes("a.png", "image/png", bytes)

    private suspend fun decode(cs: ContentSource): ImmutableImage = ImmutableImage.loader().fromBytes(cs.open().buffered().readByteArray())

    @Test
    fun `resize fit bounds within box`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Resize(30, 30, ResizeMode.FIT)))
            val img = decode(out)
            assertTrue(img.width <= 30 && img.height <= 30)
        }

    @Test
    fun `crop produces exact dimensions`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Crop(20, 20, Gravity.CENTER)))
            val img = decode(out)
            assertEquals(20, img.width)
            assertEquals(20, img.height)
        }

    @Test
    fun `convert to jpeg sets content type`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Convert(ImageFormat.JPEG, 80)))
            assertEquals("image/jpeg", out.contentType)
        }

    @Test
    fun `rotate 90 swaps dimensions`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Rotate(90)))
            val img = decode(out)
            assertEquals(40, img.width)
            assertEquals(60, img.height)
        }

    @Test
    fun `grayscale keeps dimensions`() =
        runTest {
            val out = processor.process(source(samplePng()), Variation.of(Transform.Grayscale))
            val img = decode(out)
            assertEquals(60, img.width)
            assertEquals(40, img.height)
        }
}

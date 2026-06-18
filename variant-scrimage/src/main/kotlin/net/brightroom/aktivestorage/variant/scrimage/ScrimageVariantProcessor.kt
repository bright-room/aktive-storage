package net.brightroom.aktivestorage.variant.scrimage

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.color.GrayscaleMethod
import com.sksamuel.scrimage.nio.JpegWriter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.webp.WebpWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Gravity
import net.brightroom.aktivestorage.ImageFormat
import net.brightroom.aktivestorage.ResizeMode
import net.brightroom.aktivestorage.Transform
import net.brightroom.aktivestorage.VariantProcessor
import net.brightroom.aktivestorage.Variation

/** Scrimage を用いた [VariantProcessor] 実装。画像全体をメモリ展開して変換する。 */
public class ScrimageVariantProcessor : VariantProcessor {
    override suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource =
        withContext(Dispatchers.Default) {
            val bytes = source.open().buffered().use { it.readByteArray() }
            var image = ImmutableImage.loader().fromBytes(bytes)

            var targetFormat = formatOf(source.contentType)
            var quality: Int? = null

            for (transform in variation.transforms) {
                when (transform) {
                    is Transform.Resize -> {
                        image = image.applyResize(transform)
                    }

                    is Transform.Crop -> {
                        image = image.applyCrop(transform)
                    }

                    is Transform.Rotate -> {
                        image = image.applyRotate(transform.degrees)
                    }

                    Transform.Grayscale -> {
                        image = image.toGrayscale(GrayscaleMethod.LUMA)
                    }

                    is Transform.Convert -> {
                        targetFormat = transform.format
                        quality = transform.quality
                    }
                }
            }

            val outBytes = image.encode(targetFormat, quality)
            ContentSource.ofBytes(
                renameTo(source.filename, targetFormat),
                contentTypeOf(targetFormat),
                outBytes,
            )
        }

    private fun ImmutableImage.applyResize(resize: Transform.Resize): ImmutableImage {
        val w = resize.width ?: width
        val h = resize.height ?: height
        return when (resize.mode) {
            ResizeMode.FIT -> max(w, h)
            ResizeMode.LIMIT -> bound(w, h)
            ResizeMode.FILL -> cover(w, h)
        }
    }

    private fun ImmutableImage.applyCrop(crop: Transform.Crop): ImmutableImage {
        val w = crop.width.coerceAtMost(width)
        val h = crop.height.coerceAtMost(height)
        val x =
            when (crop.gravity) {
                Gravity.WEST -> 0
                Gravity.EAST -> width - w
                Gravity.CENTER, Gravity.NORTH, Gravity.SOUTH -> (width - w) / 2
            }
        val y =
            when (crop.gravity) {
                Gravity.NORTH -> 0
                Gravity.SOUTH -> height - h
                Gravity.CENTER, Gravity.EAST, Gravity.WEST -> (height - h) / 2
            }
        return subimage(x, y, w, h)
    }

    private fun ImmutableImage.applyRotate(degrees: Int): ImmutableImage =
        when (((degrees % 360) + 360) % 360) {
            90 -> rotateRight()
            180 -> rotateRight().rotateRight()
            270 -> rotateLeft()
            else -> this
        }

    private fun ImmutableImage.encode(
        format: ImageFormat,
        quality: Int?,
    ): ByteArray =
        when (format) {
            ImageFormat.JPEG -> bytes(JpegWriter().withCompression(quality ?: 80))

            // PNG は可逆圧縮のため quality は非適用（常に MaxCompression）
            ImageFormat.PNG -> bytes(PngWriter.MaxCompression)

            ImageFormat.WEBP -> bytes(quality?.let { WebpWriter.DEFAULT.withQ(it) } ?: WebpWriter.DEFAULT)
        }

    private fun formatOf(contentType: String): ImageFormat =
        when (contentType.substringBefore(';').trim().lowercase()) {
            "image/jpeg", "image/jpg" -> ImageFormat.JPEG
            "image/webp" -> ImageFormat.WEBP
            else -> ImageFormat.PNG
        }

    private fun contentTypeOf(format: ImageFormat): String =
        when (format) {
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.PNG -> "image/png"
            ImageFormat.WEBP -> "image/webp"
        }

    private fun renameTo(
        filename: String,
        format: ImageFormat,
    ): String {
        val ext =
            when (format) {
                ImageFormat.JPEG -> "jpg"
                ImageFormat.PNG -> "png"
                ImageFormat.WEBP -> "webp"
            }
        val base = filename.substringBeforeLast('.', filename)
        return "$base.$ext"
    }
}

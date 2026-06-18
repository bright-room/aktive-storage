package net.brightroom.aktivestorage

/** 画像変換のバックエンド抽象。元画像を読み、variation を適用した結果を返す。 */
public fun interface VariantProcessor {
    /**
     * source を読み、variation を適用した結果を返す。
     * source / 返した ContentSource とも close は呼び出し側責務。
     * 変換は画像全体をメモリ展開する（ストリーミングは適用しない）。
     */
    public suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource
}

/** リサイズの寸法合わせ方。 */
public enum class ResizeMode { FIT, LIMIT, FILL }

/** クロップ基準位置。 */
public enum class Gravity { CENTER, NORTH, SOUTH, EAST, WEST }

/** 出力フォーマット。 */
public enum class ImageFormat { JPEG, PNG, WEBP }

/** 変換操作。 */
public sealed interface Transform {
    /** width/height は省略可（片方のみ指定可）。指定する辺は 1 以上、最低 1 辺は指定すること。 */
    public data class Resize(
        val width: Int?,
        val height: Int?,
        val mode: ResizeMode,
    ) : Transform {
        init {
            require(width != null || height != null) { "Resize requires at least one of width/height" }
            require(width == null || width >= 1) { "Resize width must be >= 1, was $width" }
            require(height == null || height >= 1) { "Resize height must be >= 1, was $height" }
        }
    }

    public data class Crop(
        val width: Int,
        val height: Int,
        val gravity: Gravity,
    ) : Transform {
        init {
            require(width >= 1) { "Crop width must be >= 1, was $width" }
            require(height >= 1) { "Crop height must be >= 1, was $height" }
        }
    }

    public data class Rotate(
        val degrees: Int,
    ) : Transform

    public data object Grayscale : Transform

    /** quality は省略可。指定する場合は 0..100（JPEG/WebP 共通レンジ）。 */
    public data class Convert(
        val format: ImageFormat,
        val quality: Int?,
    ) : Transform {
        init {
            require(quality == null || quality in 0..100) { "Convert quality must be in 0..100, was $quality" }
        }
    }
}

/** 変換操作の順序付き列。決定的な canonicalForm を持つ。 */
public class Variation private constructor(
    transforms: List<Transform>,
) {
    /** 構築時点のスナップショット（不変）。canonicalForm との乖離を防ぐ。 */
    public val transforms: List<Transform> = transforms.toList()

    /** 操作列の安定した正規化文字列。digest 算出（AktiveStorage 側）と同一性判定の基盤。 */
    public val canonicalForm: String = this.transforms.joinToString("|") { it.canonical() }

    private fun Transform.canonical(): String =
        when (this) {
            is Transform.Resize -> "resize:${width ?: "_"}x${height ?: "_"}:$mode"
            is Transform.Crop -> "crop:${width}x$height:$gravity"
            is Transform.Rotate -> "rotate:$degrees"
            Transform.Grayscale -> "grayscale"
            is Transform.Convert -> "convert:$format:q${quality ?: "_"}"
        }

    public companion object {
        public fun of(vararg transforms: Transform): Variation = Variation(transforms.asList())
    }
}

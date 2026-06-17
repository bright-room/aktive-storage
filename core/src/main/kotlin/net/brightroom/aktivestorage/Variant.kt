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
    public data class Resize(val width: Int?, val height: Int?, val mode: ResizeMode) : Transform

    public data class Crop(val width: Int, val height: Int, val gravity: Gravity) : Transform

    public data class Rotate(val degrees: Int) : Transform

    public data object Grayscale : Transform

    public data class Convert(val format: ImageFormat, val quality: Int?) : Transform
}

/** 変換操作の順序付き列。決定的な canonicalForm を持つ。 */
public class Variation private constructor(
    public val transforms: List<Transform>,
) {
    /** 操作列の安定した正規化文字列。digest 算出（AktiveStorage 側）と同一性判定の基盤。 */
    public val canonicalForm: String = transforms.joinToString("|") { it.canonical() }

    private fun Transform.canonical(): String =
        when (this) {
            is Transform.Resize -> "resize:${width ?: ""}x${height ?: "_"}:$mode"
            is Transform.Crop -> "crop:${width}x$height:$gravity"
            is Transform.Rotate -> "rotate:$degrees"
            Transform.Grayscale -> "grayscale"
            is Transform.Convert -> "convert:$format:q${quality ?: "_"}"
        }

    public companion object {
        public fun of(vararg transforms: Transform): Variation = Variation(transforms.toList())
    }
}

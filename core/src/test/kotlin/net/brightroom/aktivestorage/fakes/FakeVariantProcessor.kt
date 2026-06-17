package net.brightroom.aktivestorage.fakes

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Variation
import net.brightroom.aktivestorage.VariantProcessor

/** 入力バイト列に "+variant" を付すだけのフェイク。process 呼び出し回数を数える。 */
class FakeVariantProcessor : VariantProcessor {
    var calls = 0

    override suspend fun process(
        source: ContentSource,
        variation: Variation,
    ): ContentSource {
        calls++
        val bytes = source.open().buffered().use { it.readByteArray() }
        return ContentSource.ofBytes(
            "variant-${source.filename}",
            source.contentType,
            bytes + "+variant".encodeToByteArray(),
        )
    }
}

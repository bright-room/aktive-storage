package net.brightroom.aktivestorage.storage.s3

import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.io.SdkSource
import aws.smithy.kotlin.runtime.io.source
import kotlinx.io.asInputStream
import kotlinx.io.buffered
import net.brightroom.aktivestorage.ContentSource

/**
 * Streams a [ContentSource] to S3 without materializing it in heap.
 * Replayable ([isOneShot] = false): each [readFrom] re-opens the source, so the SDK may
 * re-read the body for signing or retries (the prior `fromBytes` body was replayable too).
 */
internal class ContentSourceByteStream(
    private val content: ContentSource,
    override val contentLength: Long?,
) : ByteStream.SourceStream() {
    override val isOneShot: Boolean = false

    override fun readFrom(): SdkSource =
        content
            .open()
            .buffered()
            .asInputStream()
            .source()
}

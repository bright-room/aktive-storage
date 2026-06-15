package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class AttachRollbackTest {
    /** put が常に失敗する StorageService。 */
    private class FailingStorageService(
        override val name: String = "failing",
    ) : StorageService {
        override suspend fun put(
            key: String,
            content: ContentSource,
            meta: ObjectMetadata,
        ): Unit = throw IllegalStateException("boom")

        override suspend fun get(key: String): RawSource = throw UnsupportedOperationException()

        override suspend fun exists(key: String): Boolean = false

        override suspend fun delete(key: String) = Unit

        override suspend fun presignedGetUrl(
            key: String,
            ttl: Duration,
        ): PresignedUrl? = null
    }

    @Test
    fun `attach rolls back blob row when storage put fails`() =
        runTest {
            val metadata = InMemoryMetadataStore()
            val sut =
                AktiveStorage(
                    service = FailingStorageService(),
                    metadata = metadata,
                    signer = HmacReferenceSigner("k".encodeToByteArray()),
                )

            assertFailsWith<IllegalStateException> {
                sut.attach(
                    record = RecordRef("User", "1"),
                    name = "avatar",
                    content = ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()),
                )
            }

            assertTrue(metadata.blobs.isEmpty())
            assertEquals(0, metadata.attachments.size)
        }
}

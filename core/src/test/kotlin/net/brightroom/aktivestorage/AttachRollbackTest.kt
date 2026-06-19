package net.brightroom.aktivestorage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
                    signer = HmacReferenceSigner(ByteArray(32) { 1 }),
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

    @Test
    fun `attach rollback runs even when the scope is cancelled during put`() =
        runTest {
            val backing = InMemoryMetadataStore()
            var rolledBack = false
            val metadata =
                object : MetadataStore by backing {
                    override suspend fun deleteBlob(id: BlobId) {
                        // 本物の suspend アダプタはキャンセル済みスコープで中断する。NonCancellable で包めば実行される。
                        currentCoroutineContext().ensureActive()
                        backing.deleteBlob(id)
                        rolledBack = true
                    }
                }
            val putStarted = CompletableDeferred<Unit>()
            val service =
                object : StorageService {
                    override val name = "slow"

                    override suspend fun put(
                        key: String,
                        content: ContentSource,
                        meta: ObjectMetadata,
                    ) {
                        putStarted.complete(Unit)
                        delay(10_000) // suspend until cancelled
                    }

                    override suspend fun get(key: String): RawSource = throw UnsupportedOperationException()

                    override suspend fun exists(key: String): Boolean = false

                    override suspend fun delete(key: String) = Unit

                    override suspend fun presignedGetUrl(
                        key: String,
                        ttl: Duration,
                    ): PresignedUrl? = null
                }
            val sut = AktiveStorage(service, metadata, HmacReferenceSigner(ByteArray(32) { 1 }))

            val job =
                launch {
                    runCatching {
                        sut.attach(RecordRef("User", "1"), "avatar", ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()))
                    }
                }
            putStarted.await()
            job.cancelAndJoin()

            assertTrue(rolledBack, "compensation must run under NonCancellable")
            assertTrue(backing.blobs.isEmpty())
        }
}

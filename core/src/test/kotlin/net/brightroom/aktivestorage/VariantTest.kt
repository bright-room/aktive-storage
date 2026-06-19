package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.fakes.FakeVariantProcessor
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class VariantTest {
    private fun storage(
        service: InMemoryStorageService,
        metadata: InMemoryMetadataStore,
        processor: VariantProcessor? = FakeVariantProcessor(),
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner(ByteArray(32) { 1 }),
        variantProcessor = processor,
    )

    private suspend fun attachImage(sut: AktiveStorage): Blob {
        val att =
            sut.attach(
                RecordRef("User", "1"),
                "avatar",
                ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()),
            )
        return sut.blobOf(att)!!
    }

    @Test
    fun `variant generates derived blob and stores it`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val origin = attachImage(sut)

            val v = sut.variant(origin, Variation.of(Transform.Grayscale))

            assertEquals(origin.serviceName, v.serviceName)
            assertTrue(v.key.startsWith("${origin.key}/variants/"))
            assertContentEquals("PNG+variant".encodeToByteArray(), service.objects.getValue(v.key))
        }

    @Test
    fun `variant is reused on second call`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val processor = FakeVariantProcessor()
            val sut = storage(service, metadata, processor)
            val origin = attachImage(sut)
            val variation = Variation.of(Transform.Grayscale)

            val first = sut.variant(origin, variation)
            val second = sut.variant(origin, variation)

            assertEquals(first.id, second.id)
            assertEquals(1, processor.calls)
        }

    @Test
    fun `variant without processor fails`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata, processor = null)
            val origin = attachImage(sut)

            assertFailsWith<IllegalStateException> {
                sut.variant(origin, Variation.of(Transform.Grayscale))
            }
        }

    @Test
    fun `variant blob is deliverable as a normal blob`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val origin = attachImage(sut)
            val v = sut.variant(origin, Variation.of(Transform.Grayscale))

            val token = sut.signedReference(v, 5.minutes)
            val delivery = sut.resolveForDelivery(token)
            assertTrue(delivery is Delivery.Proxy)
            val bytes = (delivery as Delivery.Proxy).stream.buffered().readByteArray()
            assertContentEquals("PNG+variant".encodeToByteArray(), bytes)
        }

    @Test
    fun `variant on a blob owned by another service fails`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val origin = attachImage(sut)
            val foreign = origin.copy(serviceName = "other")

            assertFailsWith<IllegalStateException> {
                sut.variant(foreign, Variation.of(Transform.Grayscale))
            }
        }

    @Test
    fun `concurrent insert conflict falls back to the existing variant`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            // insertVariant が一意制約違反で失敗するが、競合相手が既に記録済みの状況を模す。
            val racing =
                object : MetadataStore by metadata {
                    override suspend fun insertVariant(
                        originBlobId: BlobId,
                        variationDigest: String,
                        variant: Blob,
                    ) {
                        // 競合相手の記録を先に入れてから、自分の挿入は失敗させる。
                        metadata.insertVariant(originBlobId, variationDigest, variant.copy(id = BlobId("winner")))
                        throw IllegalStateException("duplicate key")
                    }
                }
            val sut = storage(service, metadata)
            val origin = attachImage(sut)
            // origin は metadata に登録済み。racing を使う SUT で variant 生成。
            val racingSut =
                AktiveStorage(
                    service = service,
                    metadata = racing,
                    signer = HmacReferenceSigner(ByteArray(32) { 1 }),
                    variantProcessor = FakeVariantProcessor(),
                )

            val v = racingSut.variant(origin, Variation.of(Transform.Grayscale))

            assertEquals(BlobId("winner"), v.id)
        }
}

package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.FakeVariantProcessor
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class VariantCascadeTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_000_000_000_000)

    private fun storage(
        service: InMemoryStorageService,
        metadata: InMemoryMetadataStore,
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner("k".encodeToByteArray()),
        variantProcessor = FakeVariantProcessor(),
        clock = object : Clock { override fun now(): Instant = fixedNow },
    )

    private suspend fun attachWithVariant(
        sut: AktiveStorage,
        metadata: InMemoryMetadataStore,
    ): Pair<Attachment, Blob> {
        val att = sut.attach(RecordRef("User", "1"), "avatar", ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()))
        val origin = sut.blobOf(att)!!
        val v = sut.variant(origin, Variation.of(Transform.Grayscale))
        return att to v
    }

    @Test
    fun `detach purge cascades to variants`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (att, variant) = attachWithVariant(sut, metadata)

            sut.detach(att, purgeBlob = true)

            assertNull(metadata.findBlob(variant.id))
            assertFalse(service.objects.containsKey(variant.key))
            assertTrue(metadata.variants.isEmpty())
        }

    @Test
    fun `reclaim does not reclaim variant blobs of attached origin`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (_, variant) = attachWithVariant(sut, metadata)

            val reclaimed = sut.reclaimUnattached(fixedNow + 1.hours)

            assertEquals(0, reclaimed)
            assertTrue(service.objects.containsKey(variant.key))
            assertTrue(metadata.findBlob(variant.id) != null)
        }

    @Test
    fun `reclaim cascades variants of an orphan origin`() =
        runTest {
            val service = InMemoryStorageService()
            val metadata = InMemoryMetadataStore()
            val sut = storage(service, metadata)
            val (att, variant) = attachWithVariant(sut, metadata)
            sut.detach(att, purgeBlob = false)

            val reclaimed = sut.reclaimUnattached(fixedNow + 1.hours)

            assertEquals(1, reclaimed)
            assertNull(metadata.findBlob(variant.id))
            assertFalse(service.objects.containsKey(variant.key))
        }
}

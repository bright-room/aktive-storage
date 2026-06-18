package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class DeliveryTest {
    private val record = RecordRef("User", "42")

    private fun sut(
        s: InMemoryStorageService,
        m: InMemoryMetadataStore,
    ) = AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    @Test
    fun `presign-capable service yields Redirect`() =
        runTest {
            val s = InMemoryStorageService(presignSupported = true)
            val m = InMemoryMetadataStore()
            val st = sut(s, m)
            val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val token = st.signedReference(assertNotNull(m.findBlob(att.blobId)), 5.minutes)
            assertIs<Delivery.Redirect>(st.resolveForDelivery(token))
        }

    @Test
    fun `non-presign service yields Proxy with bytes`() =
        runTest {
            val s = InMemoryStorageService(presignSupported = false)
            val m = InMemoryMetadataStore()
            val st = sut(s, m)
            val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "data".encodeToByteArray()))
            val token = st.signedReference(assertNotNull(m.findBlob(att.blobId)), 5.minutes)
            val delivery = st.resolveForDelivery(token)
            val proxy = assertIs<Delivery.Proxy>(delivery)
            proxy.stream.use {
                assertContentEquals("data".encodeToByteArray(), it.buffered().readByteArray())
            }
        }

    @Test
    fun `invalid token yields null`() =
        runTest {
            val st = sut(InMemoryStorageService(), InMemoryMetadataStore())
            assertNull(st.resolveForDelivery("garbage"))
        }

    @Test
    fun `resolveForDelivery returns null for a blob owned by another service`() =
        runTest {
            val s = InMemoryStorageService(name = "s3", presignSupported = true)
            val m = InMemoryMetadataStore()
            val st = sut(s, m)
            val att = st.attach(record, "avatar", ContentSource.ofBytes("a.png", "image/png", "x".encodeToByteArray()))
            val blob = st.blobOf(att)!!
            m.blobs[blob.id.value] = blob.copy(serviceName = "other")
            val token = st.signedReference(blob, 5.minutes)
            assertNull(st.resolveForDelivery(token))
        }
}

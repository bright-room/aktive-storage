package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class HmacReferenceSignerTest {
    private val key = "test-secret".encodeToByteArray()
    private val now = Instant.fromEpochMilliseconds(1_000_000)
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Test
    fun `sign then verify round-trips the blob id`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now + 1.hours)
        assertEquals(BlobId("b1"), signer.verify(token))
    }

    @Test
    fun `expired token verifies to null`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now - 1.hours)
        assertNull(signer.verify(token))
    }

    @Test
    fun `tampered token verifies to null`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now + 1.hours)
        assertNull(signer.verify(token + "x"))
    }

    @Test
    fun `wrong key verifies to null`() {
        val token = HmacReferenceSigner(key, fixedClock).sign(BlobId("b1"), now + 1.hours)
        assertNull(HmacReferenceSigner("other".encodeToByteArray(), fixedClock).verify(token))
    }
}

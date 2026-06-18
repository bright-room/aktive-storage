package net.brightroom.aktivestorage

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant

/** HMAC-SHA256 で BlobId+失効時刻を署名した不透明トークンを発行・検証する。 */
public class HmacReferenceSigner(
    secretKey: ByteArray,
    private val clock: Clock = Clock.System,
) : ReferenceSigner {
    init {
        require(secretKey.size >= 32) { "HMAC secret key must be >= 32 bytes, was ${secretKey.size}" }
    }

    private val keySpec = SecretKeySpec(secretKey, ALGORITHM)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun sign(
        blobId: BlobId,
        expiresAt: Instant,
    ): String {
        val payload = "${blobId.value}:${expiresAt.toEpochMilliseconds()}"
        val payloadB64 = encoder.encodeToString(payload.encodeToByteArray())
        val mac = encoder.encodeToString(hmac(payloadB64))
        return "$payloadB64.$mac"
    }

    override fun verify(token: String): BlobId? {
        val parts = token.split(".")
        if (parts.size != 2) return null
        val (payloadB64, macB64) = parts
        val expectedMac = hmac(payloadB64)
        val actualMac = runCatching { decoder.decode(macB64) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expectedMac, actualMac)) return null

        val payload = runCatching { decoder.decode(payloadB64).decodeToString() }.getOrNull() ?: return null
        val sep = payload.lastIndexOf(':')
        if (sep < 0) return null
        val expiresAt = payload.substring(sep + 1).toLongOrNull() ?: return null
        if (expiresAt <= clock.now().toEpochMilliseconds()) return null
        return BlobId(payload.substring(0, sep))
    }

    private fun hmac(data: String): ByteArray = Mac.getInstance(ALGORITHM).apply { init(keySpec) }.doFinal(data.encodeToByteArray())

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}

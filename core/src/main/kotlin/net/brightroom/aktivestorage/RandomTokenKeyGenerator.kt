package net.brightroom.aktivestorage

import java.security.SecureRandom
import java.util.Base64

/** 不透明・推測不能なランダムトークンを既定キーとする。 */
public class RandomTokenKeyGenerator(
    private val byteLength: Int = 20,
) : KeyGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generate(context: KeyContext): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}

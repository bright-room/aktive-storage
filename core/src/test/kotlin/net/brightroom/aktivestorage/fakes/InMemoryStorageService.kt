package net.brightroom.aktivestorage.fakes

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import kotlin.time.Duration

/** presignedGetUrl を null/非null で切り替えられるフェイク。 */
class InMemoryStorageService(
    override val name: String = "memory",
    private val presignSupported: Boolean = false,
) : StorageService {
    val objects = mutableMapOf<String, ByteArray>()

    override suspend fun put(
        key: String,
        content: ContentSource,
        meta: ObjectMetadata,
    ) {
        objects[key] = content.open().use { it.buffered().readByteArray() }
    }

    override suspend fun get(key: String): RawSource = Buffer().also { it.write(objects.getValue(key)) }

    override suspend fun exists(key: String): Boolean = objects.containsKey(key)

    override suspend fun delete(key: String) {
        objects.remove(key)
    }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): PresignedUrl? = if (presignSupported) PresignedUrl("https://example.test/$key") else null
}

package net.brightroom.aktivestorage.storage.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import java.util.UUID
import kotlin.time.Duration

/** ローカルFSアダプタ。presigned 非対応（get で proxy 配信される）。 */
public class FilesystemStorageService(
    private val root: Path,
    override val name: String = "fs",
) : StorageService {
    override suspend fun put(
        key: String,
        content: ContentSource,
        meta: ObjectMetadata,
    ): Unit =
        withContext(Dispatchers.IO) {
            val target = resolveSafe(key)
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            val tmp = Path(target.parent.toString(), "${target.name}.tmp-${UUID.randomUUID()}")
            content.open().use { src ->
                SystemFileSystem.sink(tmp).buffered().use { sink -> sink.transferFrom(src) }
            }
            SystemFileSystem.atomicMove(tmp, target)
        }

    override suspend fun get(key: String): RawSource = withContext(Dispatchers.IO) { SystemFileSystem.source(resolveSafe(key)) }

    override suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) { SystemFileSystem.exists(resolveSafe(key)) }

    override suspend fun delete(key: String): Unit =
        withContext(Dispatchers.IO) { SystemFileSystem.delete(resolveSafe(key), mustExist = false) }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): PresignedUrl? = null

    private fun resolveSafe(key: String): Path {
        val parts = key.split('/')
        require(key.isNotBlank() && parts.none { it == ".." || it == "." || it.isEmpty() }) {
            "invalid storage key: $key"
        }
        return Path(root.toString(), *parts.toTypedArray())
    }
}

package net.brightroom.aktivestorage.storage.fs

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FilesystemStorageServiceTest {
    private fun service(dir: java.io.File) = FilesystemStorageService(Path(dir.absolutePath))

    private fun bytes(b: String) = ObjectMetadata("text/plain", b.length.toLong(), "chk")

    @Test
    fun `put then get round-trips bytes`(
        @TempDir dir: java.io.File,
    ) = runTest {
        val s = service(dir)
        s.put("a/b/key1", ContentSource.ofBytes("f", "text/plain", "hello".encodeToByteArray()), bytes("hello"))
        assertTrue(s.exists("a/b/key1"))
        assertContentEquals("hello".encodeToByteArray(), s.get("a/b/key1").buffered().readByteArray())
    }

    @Test
    fun `delete removes object`(
        @TempDir dir: java.io.File,
    ) = runTest {
        val s = service(dir)
        s.put("k", ContentSource.ofBytes("f", "text/plain", "x".encodeToByteArray()), bytes("x"))
        s.delete("k")
        assertFalse(s.exists("k"))
    }

    @Test
    fun `presignedGetUrl is null for fs`(
        @TempDir dir: java.io.File,
    ) = runTest {
        assertNull(service(dir).presignedGetUrl("k", 5.minutes))
    }

    @Test
    fun `rejects path traversal keys`(
        @TempDir dir: java.io.File,
    ) = runTest {
        assertFailsWith<IllegalArgumentException> {
            service(dir).put("../escape", ContentSource.ofBytes("f", "text/plain", "x".encodeToByteArray()), bytes("x"))
        }
    }

    @Test
    fun `rejects backslash path traversal keys`(
        @TempDir dir: java.io.File,
    ) = runTest {
        assertFailsWith<IllegalArgumentException> {
            service(dir).put("..\\escape", ContentSource.ofBytes("f", "text/plain", "x".encodeToByteArray()), bytes("x"))
        }
    }
}

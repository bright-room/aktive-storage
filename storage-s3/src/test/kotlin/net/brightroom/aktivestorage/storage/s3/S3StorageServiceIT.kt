package net.brightroom.aktivestorage.storage.s3

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StorageServiceIT {
    private lateinit var minio: MinIOContainer
    private lateinit var client: S3Client
    private val bucket = "test-bucket"

    @BeforeAll
    fun setup() {
        runBlocking {
            minio = MinIOContainer("minio/minio").also { it.start() }
            client =
                S3Client {
                    region = "us-east-1"
                    endpointUrl = Url.parse(minio.s3URL)
                    forcePathStyle = true
                    credentialsProvider =
                        StaticCredentialsProvider(
                            Credentials(minio.userName, minio.password),
                        )
                }
            client.createBucket { this.bucket = this@S3StorageServiceIT.bucket }
        }
    }

    @AfterAll
    fun teardown() {
        client.close()
        minio.stop()
    }

    private fun service() = S3StorageService(client, bucket)

    private fun meta(b: String) = ObjectMetadata("text/plain", b.length.toLong(), "chk")

    @Test
    fun `put get delete round-trip`() =
        runBlocking {
            val s = service()
            s.put("k1", ContentSource.ofBytes("f", "text/plain", "hello".encodeToByteArray()), meta("hello"))
            val bytes = s.get("k1").buffered().use { it.readByteArray() }
            assertContentEquals("hello".encodeToByteArray(), bytes)
            s.delete("k1")
            assertFailsWith<Exception> { s.get("k1") }
        }

    @Test
    fun `put and get stream a large object without corruption`() =
        runBlocking {
            val s = service()
            val size = 16 * 1024 * 1024 // 16 MiB
            val payload = ByteArray(size) { (it % 251).toByte() }
            s.put(
                "big",
                ContentSource.ofBytes("big.bin", "application/octet-stream", payload),
                ObjectMetadata("application/octet-stream", size.toLong(), "chk"),
            )
            val readBack = s.get("big").buffered().use { it.readByteArray() }
            assertContentEquals(payload, readBack)
            s.delete("big")
        }

    @Test
    fun `get streams via a temp file that is deleted on close`() =
        runBlocking {
            val s = service()
            s.put("k3", ContentSource.ofBytes("f", "text/plain", "stream-me".encodeToByteArray()), meta("stream-me"))

            val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"))

            fun spoolFiles() = tmpDir.listFiles { _, n -> n.startsWith("aktive-s3-") }?.map { it.name }?.toSet() ?: emptySet()
            val before = spoolFiles()

            val source = s.get("k3")
            val bytes = source.buffered().use { it.readByteArray() }
            assertContentEquals("stream-me".encodeToByteArray(), bytes)

            // Compare the file *set*, not a count, so unrelated temp files can't perturb the result.
            assertEquals(emptySet(), spoolFiles() - before, "get() must leave no temp file behind after close")
            s.delete("k3")
        }

    @Test
    fun `presignedGetUrl returns a fetchable url`() =
        runBlocking {
            val s = service()
            s.put("k2", ContentSource.ofBytes("f", "text/plain", "data".encodeToByteArray()), meta("data"))
            val url = s.presignedGetUrl("k2", 5.minutes)
            val conn =
                java.net.URI
                    .create(url.value)
                    .toURL()
                    .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val fetched = conn.inputStream.use { it.readBytes() }
            assertContentEquals("data".encodeToByteArray(), fetched)
        }
}

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun `put get exists delete round-trip`() =
        runBlocking {
            val s = service()
            s.put("k1", ContentSource.ofBytes("f", "text/plain", "hello".encodeToByteArray()), meta("hello"))
            assertTrue(s.exists("k1"))
            assertContentEquals("hello".encodeToByteArray(), s.get("k1").buffered().readByteArray())
            s.delete("k1")
            assertFalse(s.exists("k1"))
        }

    @Test
    fun `presignedGetUrl returns a fetchable url`() =
        runBlocking {
            val s = service()
            s.put("k2", ContentSource.ofBytes("f", "text/plain", "data".encodeToByteArray()), meta("data"))
            val url = s.presignedGetUrl("k2", 5.minutes)
            val fetched =
                java.net.URI
                    .create(url.value)
                    .toURL()
                    .readBytes()
            assertContentEquals("data".encodeToByteArray(), fetched)
        }
}

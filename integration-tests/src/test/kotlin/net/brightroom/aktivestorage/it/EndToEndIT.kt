package net.brightroom.aktivestorage.it

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.AktiveStorage
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Delivery
import net.brightroom.aktivestorage.HmacReferenceSigner
import net.brightroom.aktivestorage.RecordRef
import net.brightroom.aktivestorage.metadata.exposed.ExposedMetadataStore
import net.brightroom.aktivestorage.storage.s3.S3StorageService
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndIT {
    private lateinit var minio: MinIOContainer
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var client: S3Client
    private lateinit var storage: AktiveStorage

    @BeforeAll
    fun setup() {
        runBlocking {
            minio = MinIOContainer("minio/minio").also { it.start() }
            pg = PostgreSQLContainer("postgres:17").also { it.start() }
            client =
                S3Client {
                    region = "us-east-1"
                    endpointUrl = Url.parse(minio.s3URL)
                    forcePathStyle = true
                    credentialsProvider = StaticCredentialsProvider(Credentials(minio.userName, minio.password))
                }
            client.createBucket { bucket = "e2e" }
            val metadata =
                ExposedMetadataStore(
                    Database.connect(pg.jdbcUrl, user = pg.username, password = pg.password),
                ).also { it.createSchema() }
            storage =
                AktiveStorage(
                    service = S3StorageService(client, "e2e"),
                    metadata = metadata,
                    signer = HmacReferenceSigner("e2e-secret".encodeToByteArray()),
                )
        }
    }

    @AfterAll
    fun teardown() {
        client.close()
        minio.stop()
        pg.stop()
    }

    @Test
    fun `attach persists, stores, and serves via signed redirect`() =
        runBlocking {
            val payload = "the-bytes".encodeToByteArray()
            val record = RecordRef("User", "42")

            val att = storage.attach(record, "avatar", ContentSource.ofBytes("a.png", "image/png", payload))

            assertContentEquals(
                listOf(att.blobId),
                storage.attachments(record, "avatar").map { it.blobId },
            )

            val blob = storage.blobOf(att)!!
            val token = storage.signedReference(blob, 5.minutes)
            val delivery = storage.resolveForDelivery(token)
            val redirect = assertIs<Delivery.Redirect>(delivery)
            val fetched =
                java.net.URI
                    .create(redirect.url.value)
                    .toURL()
                    .readBytes()
            assertContentEquals(payload, fetched)
        }
}

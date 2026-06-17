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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
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
            val conn =
                java.net.URI
                    .create(redirect.url.value)
                    .toURL()
                    .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val fetched = conn.inputStream.use { it.readBytes() }
            assertContentEquals(payload, fetched)
        }

    @Test
    fun `attach and serve a large payload end to end`() =
        runBlocking {
            val size = 16 * 1024 * 1024 // 16 MiB through spool -> S3 put -> presigned GET
            val payload = ByteArray(size) { (it % 251).toByte() }
            val record = RecordRef("User", "big")

            val att = storage.attach(record, "dump", ContentSource.ofBytes("dump.bin", "application/octet-stream", payload))
            val blob = storage.blobOf(att)!!
            val token = storage.signedReference(blob, 5.minutes)
            val redirect = assertIs<Delivery.Redirect>(storage.resolveForDelivery(token))

            val conn =
                java.net.URI
                    .create(redirect.url.value)
                    .toURL()
                    .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            val fetched = conn.inputStream.use { it.readBytes() }
            assertContentEquals(payload, fetched)
        }

    @Test
    fun `detach purges blob and object, and reclaim sweeps a kept-but-orphaned blob`() =
        runBlocking {
            val record = RecordRef("User", "del")
            val att = storage.attach(record, "avatar", ContentSource.ofBytes("a.png", "image/png", "bytes".encodeToByteArray()))

            // purge=true で実体と Blob 行が消える（blobOf が null になる）
            storage.detach(att, purgeBlob = true)
            assertNull(storage.blobOf(att))

            // purge=false で残した Blob は孤立として reclaim 対象になる
            val att2 = storage.attach(record, "cover", ContentSource.ofBytes("c.png", "image/png", "more".encodeToByteArray()))
            storage.detach(att2, purgeBlob = false)

            // 猶予を now+1m に広げ、att2 由来の Blob を確実に対象化する（同一ミリ秒競合の回避）。
            // att の Blob は purge 済み、他テストは紐付き/完全 purge のため、回収は att2 の 1 件のみ
            val reclaimed = storage.reclaimUnattached(Clock.System.now() + 1.minutes)
            assertTrue(reclaimed >= 1)
            assertNull(storage.blobOf(att2))
        }

    @Test
    fun `purgeRecord removes all attachments of a record`() =
        runBlocking {
            val record = RecordRef("User", "bulk")
            val avatarAtt = storage.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
            val docAtt = storage.attach(record, "documents", ContentSource.ofBytes("d", "text/plain", "y".encodeToByteArray()))

            storage.purgeRecord(record)

            assertEquals(0, storage.attachments(record, "avatar").size)
            assertEquals(0, storage.attachments(record, "documents").size)
            assertNull(storage.blobOf(avatarAtt))
            assertNull(storage.blobOf(docAtt))
        }
}

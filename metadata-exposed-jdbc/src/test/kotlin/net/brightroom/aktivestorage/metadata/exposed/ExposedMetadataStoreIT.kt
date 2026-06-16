package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedMetadataStoreIT {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var store: ExposedMetadataStore

    private fun blob(
        id: String,
        key: String,
    ) = Blob(
        BlobId(id),
        key,
        "a.png",
        "image/png",
        3,
        "chk",
        "s3",
        Instant.fromEpochMilliseconds(0),
    )

    @BeforeAll
    fun setup() {
        pg = PostgreSQLContainer("postgres:17").also { it.start() }
        val db = Database.connect(pg.jdbcUrl, user = pg.username, password = pg.password)
        store = ExposedMetadataStore(db).also { it.createSchema() }
    }

    @AfterAll
    fun teardown() {
        pg.stop()
    }

    @Test
    fun `insert and find blob`() =
        runBlocking {
            store.insertBlob(blob("b1", "k1"))
            assertEquals("k1", store.findBlob(BlobId("b1"))!!.key)
            store.deleteBlob(BlobId("b1"))
            assertNull(store.findBlob(BlobId("b1")))
        }

    @Test
    fun `find attachments by record and name`() =
        runBlocking {
            store.insertBlob(blob("b2", "k2"))
            val record = RecordRef("User", "42")
            store.insertAttachment(Attachment(AttachmentId("a1"), "avatar", record, BlobId("b2"), Instant.fromEpochMilliseconds(0)))
            assertEquals(1, store.findAttachments(record, "avatar").size)
            assertEquals(0, store.findAttachments(record, "documents").size)
            store.deleteAttachment(AttachmentId("a1"))
            assertEquals(0, store.findAttachments(record, "avatar").size)
        }

    @Test
    fun `countAttachmentsForBlob counts only matching attachments`() =
        runBlocking {
            store.insertBlob(blob("bc", "kc"))
            val record = RecordRef("User", "count")
            store.insertAttachment(Attachment(AttachmentId("ac1"), "avatar", record, BlobId("bc"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ac2"), "cover", record, BlobId("bc"), Instant.fromEpochMilliseconds(0)))
            assertEquals(2, store.countAttachmentsForBlob(BlobId("bc")))

            store.deleteAttachment(AttachmentId("ac1"))
            assertEquals(1, store.countAttachmentsForBlob(BlobId("bc")))

            assertEquals(0, store.countAttachmentsForBlob(BlobId("no-such-blob")))
        }
}

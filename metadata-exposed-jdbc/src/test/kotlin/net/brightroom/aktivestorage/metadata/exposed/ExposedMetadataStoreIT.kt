package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedMetadataStoreIT {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var db: Database
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
        db = Database.connect(pg.jdbcUrl, user = pg.username, password = pg.password)
        store = ExposedMetadataStore(db).also { it.createSchema() }
    }

    @BeforeEach
    fun clean() {
        transaction(db) {
            VariantRecordsTable.deleteAll()
            AttachmentsTable.deleteAll()
            BlobsTable.deleteAll()
        }
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

    @Test
    fun `findUnattachedBlobs returns only old blobs with no attachment`() =
        runBlocking {
            // 古い孤立 Blob（createdAt=100, 参照なし）
            store.insertBlob(
                Blob(BlobId("u-old"), "ku-old", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(100)),
            )
            // 新しい孤立 Blob（createdAt=5000, 参照なし → 猶予内なので対象外）
            store.insertBlob(
                Blob(BlobId("u-new"), "ku-new", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(5000)),
            )
            // 古いが紐付きの Blob（createdAt=100, 参照あり → 対象外）
            store.insertBlob(
                Blob(BlobId("u-att"), "ku-att", "f", "image/png", 1, "c", "s3", Instant.fromEpochMilliseconds(100)),
            )
            store.insertAttachment(
                Attachment(AttachmentId("ua1"), "avatar", RecordRef("User", "u"), BlobId("u-att"), Instant.fromEpochMilliseconds(100)),
            )

            val found = store.findUnattachedBlobs(Instant.fromEpochMilliseconds(1000)).map { it.id.value }.toSet()

            assertEquals(setOf("u-old"), found)
        }

    @Test
    fun `findAttachmentsForRecord returns all names for the record only`() =
        runBlocking {
            store.insertBlob(blob("br1", "kr1"))
            store.insertBlob(blob("br2", "kr2"))
            store.insertBlob(blob("br3", "kr3"))
            val target = RecordRef("User", "rec")
            val other = RecordRef("User", "other")
            store.insertAttachment(Attachment(AttachmentId("ar1"), "avatar", target, BlobId("br1"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ar2"), "cover", target, BlobId("br2"), Instant.fromEpochMilliseconds(0)))
            store.insertAttachment(Attachment(AttachmentId("ar3"), "avatar", other, BlobId("br3"), Instant.fromEpochMilliseconds(0)))

            val names = store.findAttachmentsForRecord(target).map { it.name }.toSet()

            assertEquals(setOf("avatar", "cover"), names)
        }

    @Test
    fun `insert and find variant`() =
        runBlocking {
            store.insertBlob(blob("origin", "ko"))
            store.insertVariant(BlobId("origin"), "digestA", blob("variant", "kv"))

            assertEquals(BlobId("variant"), store.findVariant(BlobId("origin"), "digestA")?.id)
            assertNull(store.findVariant(BlobId("origin"), "digestB"))
            assertEquals(listOf(BlobId("variant")), store.findVariantsOf(BlobId("origin")).map { it.id })
        }

    @Test
    fun `unattached sweep excludes variant blobs`() =
        runBlocking {
            store.insertBlob(blob("origin", "ko"))
            store.insertVariant(BlobId("origin"), "digestA", blob("variant", "kv"))

            val orphans = store.findUnattachedBlobs(Instant.fromEpochMilliseconds(1000)).map { it.id.value }.toSet()
            // origin (createdAt=0, no attachment) は孤立として出るが variant は除外される
            assertTrue("origin" in orphans)
            assertFalse("variant" in orphans)
        }

    @Test
    fun `deleteVariantsOf removes records and variant blob rows`() =
        runBlocking {
            store.insertBlob(blob("origin", "ko"))
            store.insertVariant(BlobId("origin"), "digestA", blob("variant", "kv"))

            store.deleteVariantsOf(BlobId("origin"))

            assertNull(store.findVariant(BlobId("origin"), "digestA"))
            assertNull(store.findBlob(BlobId("variant")))
            assertTrue(store.findVariantsOf(BlobId("origin")).isEmpty())
        }
}

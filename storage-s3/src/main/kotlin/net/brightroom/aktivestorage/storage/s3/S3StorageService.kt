package net.brightroom.aktivestorage.storage.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.writeToFile
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import java.io.File
import java.util.UUID
import kotlin.time.Duration

/** S3 / S3互換アダプタ（AWS SDK for Kotlin, suspend ネイティブ）。 */
public class S3StorageService(
    private val client: S3Client,
    private val bucket: String,
    override val name: String = "s3",
) : StorageService {
    override suspend fun put(
        key: String,
        content: ContentSource,
        meta: ObjectMetadata,
    ) {
        client.putObject {
            this.bucket = this@S3StorageService.bucket
            this.key = key
            this.contentType = meta.contentType
            this.contentLength = meta.byteSize
            this.body = ContentSourceByteStream(content, meta.byteSize)
        }
    }

    override suspend fun get(key: String): RawSource {
        val tempFile = Path(SystemTemporaryDirectory, "aktive-s3-${UUID.randomUUID()}.tmp")
        try {
            client.getObject(
                GetObjectRequest {
                    this.bucket = this@S3StorageService.bucket
                    this.key = key
                },
            ) { resp ->
                val body = resp.body ?: error("S3 returned no body for key=$key")
                body.writeToFile(File(tempFile.toString()))
            }
            return DeletingFileSource(tempFile)
        } catch (e: Exception) {
            SystemFileSystem.delete(tempFile, mustExist = false)
            throw e
        }
    }

    override suspend fun exists(key: String): Boolean =
        try {
            client.headObject {
                this.bucket = this@S3StorageService.bucket
                this.key = key
            }
            true
        } catch (_: NotFound) {
            false
        }

    override suspend fun delete(key: String) {
        client.deleteObject {
            this.bucket = this@S3StorageService.bucket
            this.key = key
        }
    }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): PresignedUrl {
        val presigned =
            client.presignGetObject(
                GetObjectRequest {
                    this.bucket = this@S3StorageService.bucket
                    this.key = key
                },
                ttl,
            )
        return PresignedUrl(presigned.url.toString())
    }
}

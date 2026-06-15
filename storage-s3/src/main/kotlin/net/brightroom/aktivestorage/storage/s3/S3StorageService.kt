package net.brightroom.aktivestorage.storage.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
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
        val bytes = content.open().buffered().use { it.readByteArray() }
        client.putObject {
            this.bucket = this@S3StorageService.bucket
            this.key = key
            this.contentType = meta.contentType
            this.contentLength = meta.byteSize
            this.body = ByteStream.fromBytes(bytes)
        }
    }

    override suspend fun get(key: String): RawSource {
        val bytes =
            client.getObject(
                GetObjectRequest {
                    this.bucket = this@S3StorageService.bucket
                    this.key = key
                },
            ) { resp -> resp.body?.toByteArray() ?: error("S3 returned no body for key=$key") }
        return Buffer().also { it.write(bytes) }
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

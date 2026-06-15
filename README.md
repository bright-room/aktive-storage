# aktive-storage

[![Maven Central](https://img.shields.io/maven-central/v/net.bright-room.aktive-storage/core?label=Maven%20Central)](https://central.sonatype.com/namespace/net.bright-room.aktive-storage)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml/badge.svg)](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml)

**English** | [日本語](README.ja.md)

Framework-agnostic file attachment toolkit for the JVM — a Kotlin core with pluggable storage, ORM, and image-variant adapters. Inspired by Rails' ActiveStorage, but free of any web-framework or ORM lock-in.

## Modules

| Module | Coordinate | Description |
|---|---|---|
| Core | `net.bright-room.aktive-storage:core` | attach/detach, signed delivery, storage & metadata ports |
| Filesystem | `net.bright-room.aktive-storage:storage-fs` | local filesystem `StorageService` (proxy delivery) |
| S3 | `net.bright-room.aktive-storage:storage-s3` | AWS S3 `StorageService` (presigned-redirect delivery) |
| Exposed (JDBC) | `net.bright-room.aktive-storage:metadata-exposed-jdbc` | `MetadataStore` backed by JetBrains Exposed |
| BOM | `net.bright-room.aktive-storage:bom` | version alignment for all modules |

## Requirements

- JDK 21+

## Install

Gradle (Kotlin DSL) — use the BOM to align versions:

```kotlin
dependencies {
    implementation(platform("net.bright-room.aktive-storage:bom:0.0.1"))
    implementation("net.bright-room.aktive-storage:core")
    implementation("net.bright-room.aktive-storage:storage-fs")
    implementation("net.bright-room.aktive-storage:metadata-exposed-jdbc")

    // a JDBC driver for the metadata store (H2 shown for a quick try)
    runtimeOnly("com.h2database:h2:2.4.240")
}
```

## Quickstart

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import net.brightroom.aktivestorage.AktiveStorage
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Delivery
import net.brightroom.aktivestorage.HmacReferenceSigner
import net.brightroom.aktivestorage.RecordRef
import net.brightroom.aktivestorage.metadata.exposed.ExposedMetadataStore
import net.brightroom.aktivestorage.storage.fs.FilesystemStorageService
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration.Companion.minutes

fun main() = runBlocking {
    // Wire up: filesystem storage + Exposed metadata + HMAC signer for delivery tokens
    val metadata = ExposedMetadataStore(
        Database.connect("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
    ).also { it.createSchema() }

    val storage = AktiveStorage(
        service = FilesystemStorageService(Path("uploads")),
        metadata = metadata,
        signer = HmacReferenceSigner("change-me-in-production".encodeToByteArray()),
    )

    // Attach bytes to a record — e.g. User#42's "avatar"
    val attachment = storage.attach(
        RecordRef("User", "42"),
        "avatar",
        ContentSource.ofBytes("a.png", "image/png", "the-bytes".encodeToByteArray()),
    )

    // Issue a signed, time-limited delivery token, then resolve how to serve it
    val blob = storage.blobOf(attachment)!!
    val token = storage.signedReference(blob, 5.minutes)
    when (val delivery = storage.resolveForDelivery(token)) {
        is Delivery.Redirect -> println("302 redirect -> ${delivery.url.value}") // presigned services (S3)
        is Delivery.Proxy -> println("proxy ${delivery.blob.byteSize} bytes")     // filesystem
        null -> println("invalid or expired token")
    }
}
```

`storage-s3` swaps the filesystem service for S3 and serves files via presigned redirects instead of proxying. See [the integration test](integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt) for a full S3 + PostgreSQL example.

## Versioning

aktive-storage follows semantic versioning with pre-1.0 semantics:

- **`0.x` does not guarantee API stability.** A minor bump (`0.MINOR.0`) may include breaking changes; a patch bump (`0.0.PATCH`) is backward-compatible fixes only. The public API stabilizes at `1.0`.
- The public ABI is guarded in CI by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) to catch *unintended* breaks. Intentional changes regenerate the `api/*.api` dumps (`./gradlew apiDump`) and bump the minor version.

Releases are published to Maven Central on tag push.

## License

[Apache-2.0](LICENSE)

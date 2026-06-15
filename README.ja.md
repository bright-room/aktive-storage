# aktive-storage

[![Maven Central](https://img.shields.io/maven-central/v/net.bright-room.aktive-storage/core?label=Maven%20Central)](https://central.sonatype.com/namespace/net.bright-room.aktive-storage)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml/badge.svg)](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml)

[English](README.md) | **日本語**

JVM 向けのフレームワーク非依存なファイル添付ツールキット。Kotlin のコアに、ストレージ・ORM・画像バリアントをアダプタとして差し込む構成。Rails の ActiveStorage に着想を得つつ、特定の Web フレームワークや ORM へのロックインを排している。

## モジュール

| モジュール | 座標 | 説明 |
|---|---|---|
| Core | `net.bright-room.aktive-storage:core` | attach/detach、署名付き配信、ストレージ／メタデータのポート |
| Filesystem | `net.bright-room.aktive-storage:storage-fs` | ローカルファイルシステムの `StorageService`（プロキシ配信） |
| S3 | `net.bright-room.aktive-storage:storage-s3` | AWS S3 の `StorageService`（署名付き URL リダイレクト配信） |
| Exposed (JDBC) | `net.bright-room.aktive-storage:metadata-exposed-jdbc` | JetBrains Exposed を使った `MetadataStore` |
| BOM | `net.bright-room.aktive-storage:bom` | 全モジュールのバージョン整合 |

## 要件

- JDK 21+

## 導入

Gradle (Kotlin DSL) — BOM でバージョンを揃える:

```kotlin
dependencies {
    implementation(platform("net.bright-room.aktive-storage:bom:0.0.1"))
    implementation("net.bright-room.aktive-storage:core")
    implementation("net.bright-room.aktive-storage:storage-fs")
    implementation("net.bright-room.aktive-storage:metadata-exposed-jdbc")

    // メタデータストア用の JDBC ドライバ（お試し用に H2 を例示）
    runtimeOnly("com.h2database:h2:2.4.240")
}
```

## クイックスタート

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
    // 構成: ファイルシステムストレージ + Exposed メタデータ + 配信トークン用の HMAC 署名器
    val metadata = ExposedMetadataStore(
        Database.connect("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"),
    ).also { it.createSchema() }

    val storage = AktiveStorage(
        service = FilesystemStorageService(Path("uploads")),
        metadata = metadata,
        signer = HmacReferenceSigner("change-me-in-production".encodeToByteArray()),
    )

    // レコードにバイト列を添付する — 例: User#42 の "avatar"
    val attachment = storage.attach(
        RecordRef("User", "42"),
        "avatar",
        ContentSource.ofBytes("a.png", "image/png", "the-bytes".encodeToByteArray()),
    )

    // 署名付きで有効期限のある配信トークンを発行し、配信方法を解決する
    val blob = storage.blobOf(attachment)!!
    val token = storage.signedReference(blob, 5.minutes)
    when (val delivery = storage.resolveForDelivery(token)) {
        is Delivery.Redirect -> println("302 redirect -> ${delivery.url.value}") // 署名付き URL 対応サービス（S3）
        is Delivery.Proxy -> println("proxy ${delivery.blob.byteSize} bytes")     // ファイルシステム
        null -> println("invalid or expired token")
    }
}
```

`storage-s3` はファイルシステムの代わりに S3 を使い、プロキシではなく署名付き URL リダイレクトで配信する。S3 + PostgreSQL の完全な例は [統合テスト](integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt) を参照。

## バージョン方針

aktive-storage は semantic versioning に従い、1.0 未満は以下の意味論とする:

- **`0.x` は API の安定性を保証しない。** minor 上げ（`0.MINOR.0`）は破壊的変更を含みうる。patch 上げ（`0.0.PATCH`）は後方互換のバグ修正のみ。public API は `1.0` で安定する。
- public ABI は CI 上で [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) により守られ、**意図しない**破壊を検出する。意図的な変更は `api/*.api` ダンプを再生成（`./gradlew apiDump`）し、minor を上げる。

リリースはタグ Push で Maven Central に公開される（手順は [Releasing](docs/RELEASING.md)）。

## ライセンス

[Apache-2.0](LICENSE)

# aktive-storage 公開直後の足回り整備 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 公開済み v0.0.1 の aktive-storage に、使い方 README・公開 API の ABI 互換チェック・バージョン方針を整備する。

**Architecture:** ルートに binary-compatibility-validator(BCV) を適用して公開4モジュールの ABI を `api/*.api` で固定し CI で守る。README を最小 Quickstart に差し替え、バージョン方針（0.x 破壊許容）を明記する。SNAPSHOT は実装しない。

**Tech Stack:** Gradle (Kotlin DSL), kotlinx binary-compatibility-validator 0.18.1, GitHub Actions, Markdown.

**設計根拠:** `docs/superpowers/specs/2026-06-15-post-release-foundations-design.md`

**前提:**
- 作業ブランチ `feat/post-release-foundations`（最新 main から分岐済み、spec コミット済み `b9bd78a`）
- config-cache 有効（`org.gradle.configuration-cache=true`）。全 gradle コマンドは config-cache 有効のまま green になること
- 公開モジュール = core / storage-fs / storage-s3 / metadata-exposed-jdbc / bom。`integration-tests` は非公開
- 検証で確認済みの実値: BCV 0.18.1（config-cache 対応）、H2 最新 2.4.240、`Delivery.Proxy(blob, stream)` / `Delivery.Redirect(url)`、4公開モジュール（bom除く）に main ソースあり

---

## File Structure

- `build.gradle.kts`（ルート）— 修正: BCV プラグイン適用 + `apiValidation` 設定
- `core/api/core.api`、`storage-fs/api/storage-fs.api`、`storage-s3/api/storage-s3.api`、`metadata-exposed-jdbc/api/metadata-exposed-jdbc.api` — 新規（`apiDump` 生成物、基準としてコミット）
- `.github/workflows/ci.yml` — 修正: lint ジョブに `apiCheck` ステップ追加
- `README.md` — 全面差し替え（最小 Quickstart）

---

## Task 1: BCV を適用し公開 API ダンプを基準化

**Files:**
- Modify: `build.gradle.kts`
- Create: `core/api/core.api`, `storage-fs/api/storage-fs.api`, `storage-s3/api/storage-s3.api`, `metadata-exposed-jdbc/api/metadata-exposed-jdbc.api`

- [ ] **Step 1: ルート build.gradle.kts に BCV を適用**

現在の `build.gradle.kts` 全体（1行）:
```kotlin
// 規約は build-logic の precompiled script plugin に集約。ルートは意図的に空。
```
を次に置き換える:
```kotlin
// 規約は build-logic の precompiled script plugin に集約。
// ABI 互換チェック（BCV）はビルド全体への単一の関心事のためルートに適用する。
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

apiValidation {
    ignoredProjects += listOf("integration-tests", "bom")
}
```

- [ ] **Step 2: 公開 API ダンプを生成**

Run: `./gradlew apiDump`
Expected: BUILD SUCCESSFUL。`core/api/core.api` ほか計4ファイルが生成される。

確認:
```bash
ls core/api storage-fs/api storage-s3/api metadata-exposed-jdbc/api
```
Expected: 各ディレクトリに `<module>.api` が1つずつ（計4ファイル）。

> もし `apiDump` がルートプロジェクト `aktive-storage` 等、ソースを持たないプロジェクトのダンプを試みて失敗した場合は、そのプロジェクト名を `ignoredProjects` に追加して再実行し、その旨を報告すること（推測で他の変更を加えない）。

- [ ] **Step 3: apiCheck が基準と一致して通ることを確認（config-cache 込み）**

Run: `./gradlew apiCheck`
Expected: BUILD SUCCESSFUL、かつ末尾に `Configuration cache entry stored.`（config-cache 有効のまま通る）。

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts core/api storage-fs/api storage-s3/api metadata-exposed-jdbc/api
git commit -m "build: add binary-compatibility-validator and baseline public API dumps

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: CI の lint ジョブに apiCheck を組み込む

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: lint ジョブに ABI check ステップを追加**

`.github/workflows/ci.yml` の lint ジョブ末尾、現在の:
```yaml
      - name: Spotless check
        run: ./gradlew spotlessCheck
```
の直後に次のステップを追加する（`test` / `integration-test` ジョブは変更しない）:
```yaml
      - name: ABI check
        run: ./gradlew apiCheck
```

- [ ] **Step 2: YAML が正しくパースされることを確認**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('OK')"`
Expected: `OK`。

- [ ] **Step 3: ローカルで lint ジョブ相当（spotless + apiCheck）が通ることを確認**

Run: `./gradlew spotlessCheck apiCheck`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: run apiCheck in lint job to guard public ABI

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: README を最小 Quickstart に差し替える

**Files:**
- Modify: `README.md`（全面差し替え）

- [ ] **Step 1: README.md を次の内容で全面差し替え**

````markdown
# aktive-storage

[![Maven Central](https://img.shields.io/maven-central/v/net.bright-room.aktive-storage/core?label=Maven%20Central)](https://central.sonatype.com/namespace/net.bright-room.aktive-storage)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml/badge.svg)](https://github.com/bright-room/aktive-storage/actions/workflows/ci.yml)

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

Releases are cut by tag push; see the [release design](docs/superpowers/specs/2026-06-15-maven-central-first-release-design.md).

## License

[Apache-2.0](LICENSE)
````

- [ ] **Step 2: Quickstart のシンボルが実 API と一致することを確認**

Run:
```bash
grep -n "public class AktiveStorage" core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt
grep -n "fun ofBytes" core/src/main/kotlin/net/brightroom/aktivestorage/ContentSource.kt
grep -n "class FilesystemStorageService" storage-fs/src/main/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageService.kt
grep -n "class ExposedMetadataStore" metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt
```
Expected: それぞれヒットし、README の `AktiveStorage(service=, metadata=, signer=)` / `ContentSource.ofBytes(filename, contentType, bytes)` / `FilesystemStorageService(Path)` / `ExposedMetadataStore(Database)` の使い方と矛盾しないこと（コンストラクタ引数名・順序を目視確認）。`Delivery.Redirect(url).url.value` と `Delivery.Proxy(blob, stream).blob.byteSize` は `core/src/main/kotlin/net/brightroom/aktivestorage/Delivery.kt` と一致。

- [ ] **Step 3: Markdown リンクの自己整合を確認**

Run: `ls LICENSE docs/superpowers/specs/2026-06-15-maven-central-first-release-design.md integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt`
Expected: README からリンクする3ファイルが全て存在する。

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: rewrite README with quickstart, modules, and versioning policy

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 最終検証と仕上げ

**Files:**
- なし（検証 + git 操作）

- [ ] **Step 1: 全体検証（config-cache 有効のまま）**

Run: `./gradlew apiCheck spotlessCheck test`
Expected: BUILD SUCCESSFUL、末尾に `Configuration cache entry stored.`。

- [ ] **Step 2: ブランチ状態を確認**

Run: `git log --oneline origin/main..HEAD && git status`
Expected: Task 1–3 の3コミット + spec コミットが並び、working tree clean。

- [ ] **Step 3: push と PR 作成（push はユーザー承認後）**

> 注意: `require-signed-commits-branch` により push には verified 署名が要る。`commit.gpgsign=true`（SSH / 1Password）が有効なら各コミットは署名済み。push/PR はユーザーに委ねる。

```bash
git push -u origin feat/post-release-foundations
gh pr create --fill --base main
```

---

## Self-Review メモ

- **Spec coverage:** §1 README→T3 / §2 ABI(BCV root 適用・ignoredProjects・apiDump コミット・CI apiCheck)→T1+T2 / §3 バージョン方針(README Versioning 節)+リリースノート(既存自動生成のまま、変更なし)→T3 / §4 SNAPSHOT(実装しない＝タスク無し、spec 付録のみ)→意図的に対象外。
- **Placeholder:** なし（BCV 0.18.1 / H2 2.4.240 確定、README 全文記載、api ファイル名4つ明示）。
- **型/名称整合:** プラグイン id `org.jetbrains.kotlinx.binary-compatibility-validator`、拡張 `apiValidation`、タスク `apiDump`/`apiCheck` を全タスクで一貫使用。Quickstart の API は AktiveStorage/ContentSource/FilesystemStorageService/ExposedMetadataStore/Delivery のソース確認済み。

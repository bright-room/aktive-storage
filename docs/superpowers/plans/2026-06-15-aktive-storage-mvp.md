# aktive-storage フェーズ1（MVP）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ActiveStorage 相当の中核体験「モデルにファイルを添付→保存→署名付きURLで配信」を Pure Kotlin で成立させる（core + storage-fs + storage-s3 + metadata-exposed-jdbc）。

**Architecture:** フレームワーク非依存の `core`（言語中立な公開API: kotlinx-io / kotlin.time / 値クラス）にポートを定義し、各アダプタが `core` に一方向依存する依存逆転構成。Gradle マルチプロジェクト + build-logic 規約プラグイン。ウォーキングスケルトンで先にポート合成を検証し、その後アダプタを肉付けする。

**Tech Stack:** Kotlin 2.4.0 / Gradle 9.5.1 / kotlinx-coroutines 1.11.0 / kotlinx-io 0.9.0 / Exposed 1.3.0(JDBC) / AWS SDK for Kotlin 1.6.91 / JUnit 6.1.0 / Testcontainers 2.0.5(MinIO+Postgres) / Spotless 8.6.0(ktlint 1.8.0) / Dokka 2.2.0。JVM target 21。

**規約:**
- パッケージ root は `net.brightroom.aktivestorage`（groupId のハイフンは除去）。
- 全コミットは末尾に `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` を付ける。
- 各 public 宣言には `public` を明示（`explicitApi()` 有効）。
- TDD: 失敗するテスト → 失敗確認 → 最小実装 → 成功確認 → コミット。

---

## File Structure

```
aktive-storage/
├─ settings.gradle.kts                      # ルート: モジュール登録 + version catalog
├─ build.gradle.kts                         # ルート: 空（規約は build-logic）
├─ gradle.properties                        # JVM/Gradle 設定
├─ .editorconfig                            # ktlint ルール
├─ .gitignore
├─ gradle/libs.versions.toml                # バージョンカタログ（唯一の版の源）
├─ build-logic/
│  ├─ settings.gradle.kts                   # catalog 取り込み
│  ├─ build.gradle.kts                      # kotlin-dsl + プラグイン依存
│  └─ src/main/kotlin/
│     ├─ aktive.kotlin-library.gradle.kts   # kotlin-jvm/toolchain21/explicitApi/junit
│     ├─ aktive.integration-test.gradle.kts # integrationTest タスク（タグ分離）
│     └─ aktive.published.gradle.kts        # maven-publish/signing/sources/javadoc(dokka)
├─ core/
│  ├─ build.gradle.kts
│  └─ src/main/kotlin/net/brightroom/aktivestorage/
│     ├─ Ids.kt              # BlobId / AttachmentId / PresignedUrl
│     ├─ RecordRef.kt
│     ├─ Blob.kt / Attachment.kt
│     ├─ ContentSource.kt    # ContentSource + ByteArrayContentSource + ObjectMetadata + KeyContext
│     ├─ Ports.kt            # StorageService / MetadataStore / KeyGenerator / ReferenceSigner
│     ├─ RandomTokenKeyGenerator.kt
│     ├─ HmacReferenceSigner.kt
│     ├─ Delivery.kt         # Delivery sealed interface
│     ├─ Spool.kt            # internal: スプール + MD5
│     └─ AktiveStorage.kt    # facade
├─ storage-fs/    src/main/.../storage/fs/FilesystemStorageService.kt
├─ storage-s3/    src/main/.../storage/s3/S3StorageService.kt
├─ metadata-exposed-jdbc/ src/main/.../metadata/exposed/{Tables.kt,ExposedMetadataStore.kt}
├─ bom/           build.gradle.kts (java-platform)
├─ integration-tests/  src/test/... (E2E, 非公開)
└─ .github/workflows/{ci.yml,verify-packaging.yml} + renovate.json
```

---

## Task 0: プロジェクト骨格とツールチェイン spike

**Files:**
- Create: `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.editorconfig`, `.gitignore`
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/aktive.kotlin-library.gradle.kts`
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/net/brightroom/aktivestorage/Placeholder.kt`

- [ ] **Step 1: Gradle wrapper を 9.5.1 で生成**

Run: `gradle wrapper --gradle-version 9.5.1` （`gradle` 未導入なら一旦任意の gradle で生成後、`gradle/wrapper/gradle-wrapper.properties` の `distributionUrl` を `gradle-9.5.1-bin.zip` に手修正して `./gradlew wrapper --gradle-version 9.5.1`）
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` が生成。`./gradlew --version` が Gradle 9.5.1 を表示。

- [ ] **Step 2: バージョンカタログを作成**

Create `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.0"
coroutines = "1.11.0"
kotlinx-io = "0.9.0"
exposed = "1.3.0"
aws = "1.6.91"
testcontainers = "2.0.5"
junit = "6.1.0"
postgresql = "42.7.11"
spotless = "8.6.0"
ktlint = "1.8.0"
dokka = "2.2.0"

[libraries]
# build-logic が適用するプラグインを依存として参照する
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
spotless-gradle-plugin = { module = "com.diffplug.spotless:spotless-plugin-gradle", version.ref = "spotless" }
dokka-gradle-plugin = { module = "org.jetbrains.dokka:dokka-gradle-plugin", version.ref = "dokka" }

# runtime
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-io-core = { module = "org.jetbrains.kotlinx:kotlinx-io-core", version.ref = "kotlinx-io" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
aws-s3 = { module = "aws.sdk.kotlin:s3", version.ref = "aws" }

# test
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
testcontainers-junit = { module = "org.testcontainers:testcontainers-junit-jupiter", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql", version.ref = "testcontainers" }
testcontainers-minio = { module = "org.testcontainers:testcontainers-minio", version.ref = "testcontainers" }
postgresql-driver = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
```

- [ ] **Step 3: gradle.properties / .gitignore / .editorconfig を作成**

Create `gradle.properties`:

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

Create `.gitignore`:

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.kotlin/
```

Create `.editorconfig`:

```editorconfig
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 4

[*.{kt,kts}]
ktlint_standard_filename = disabled
```

- [ ] **Step 4: build-logic（規約プラグイン）を作成**

Create `build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "build-logic"
```

Create `build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}
```

Create `build-logic/src/main/kotlin/aktive.kotlin-library.gradle.kts`:

```kotlin
import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.diffplug.spotless")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

extensions.configure<SpotlessExtension> {
    kotlin {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
        target("src/**/*.kt")
    }
    kotlinGradle {
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}
```

- [ ] **Step 5: ルート settings/build と core スタブを作成**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aktive-storage"

include("core")
```

Create `build.gradle.kts` (root, empty):

```kotlin
// 規約は build-logic の precompiled script plugin に集約。ルートは意図的に空。
```

Create `core/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.io.core)
}
```

Create `core/src/main/kotlin/net/brightroom/aktivestorage/Placeholder.kt`:

```kotlin
package net.brightroom.aktivestorage

internal const val PLACEHOLDER: String = "skeleton"
```

- [ ] **Step 6: toolchain spike を検証（最重要・失敗時は Kotlin 2.3.20 へ）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。失敗（Kotlin 2.4.0 と Gradle 9.5.1/Dokka/KGP の不整合）した場合は `gradle/libs.versions.toml` の `kotlin = "2.3.20"` に変更して再実行。spotlessCheck も通ること: `./gradlew spotlessCheck`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: scaffold gradle multi-project skeleton (toolchain spike)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 1: core ドメイン型

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Ids.kt`
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/RecordRef.kt`
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Blob.kt`
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Attachment.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/DomainTypesTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DomainTypesTest {
    @Test
    fun `blob holds key and metadata distinctly from id`() {
        val blob = Blob(
            id = BlobId("b1"),
            key = "token-abc",
            filename = "a.png",
            contentType = "image/png",
            byteSize = 3,
            checksum = "chk",
            serviceName = "fs",
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        assertEquals(BlobId("b1"), blob.id)
        assertEquals("token-abc", blob.key)
    }

    @Test
    fun `attachment references a record and a blob`() {
        val att = Attachment(
            id = AttachmentId("a1"),
            name = "avatar",
            record = RecordRef("User", "42"),
            blobId = BlobId("b1"),
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        assertEquals(RecordRef("User", "42"), att.record)
        assertEquals(BlobId("b1"), att.blobId)
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test`
Expected: FAIL（`BlobId` / `Blob` / `Attachment` / `RecordRef` 未定義でコンパイルエラー）

- [ ] **Step 3: 型を実装**

Create `Ids.kt`:

```kotlin
package net.brightroom.aktivestorage

@JvmInline
public value class BlobId(public val value: String)

@JvmInline
public value class AttachmentId(public val value: String)

@JvmInline
public value class PresignedUrl(public val value: String)
```

Create `RecordRef.kt`:

```kotlin
package net.brightroom.aktivestorage

public data class RecordRef(public val type: String, public val id: String)
```

Create `Blob.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlin.time.Instant

public data class Blob(
    public val id: BlobId,
    public val key: String,
    public val filename: String,
    public val contentType: String,
    public val byteSize: Long,
    public val checksum: String,
    public val serviceName: String,
    public val createdAt: Instant,
)
```

Create `Attachment.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlin.time.Instant

public data class Attachment(
    public val id: AttachmentId,
    public val name: String,
    public val record: RecordRef,
    public val blobId: BlobId,
    public val createdAt: Instant,
)
```

- [ ] **Step 4: テスト成功を確認**

Run: `./gradlew :core:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): add domain types (Blob, Attachment, RecordRef, ids)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: core サポート型（ContentSource / ObjectMetadata / KeyContext）

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/ContentSource.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/ContentSourceTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ContentSourceTest {
    @Test
    fun `byte-backed source re-opens fresh streams`() {
        val src = ContentSource.ofBytes("a.txt", "text/plain", "hello".encodeToByteArray())
        assertEquals("a.txt", src.filename)
        assertEquals("text/plain", src.contentType)
        val first = src.open().use { it.buffered().readByteArray() }
        val second = src.open().use { it.buffered().readByteArray() }
        assertContentEquals("hello".encodeToByteArray(), first)
        assertContentEquals("hello".encodeToByteArray(), second)
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*ContentSourceTest"`
Expected: FAIL（`ContentSource` 未定義）

- [ ] **Step 3: 実装**

Create `ContentSource.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/** アップロード元バイト列とそのメタ情報。`open()` は毎回新しいストリームを返す。 */
public interface ContentSource {
    public val filename: String
    public val contentType: String
    public fun open(): RawSource

    public companion object {
        public fun ofBytes(filename: String, contentType: String, bytes: ByteArray): ContentSource =
            ByteArrayContentSource(filename, contentType, bytes)
    }
}

internal class ByteArrayContentSource(
    override val filename: String,
    override val contentType: String,
    private val bytes: ByteArray,
) : ContentSource {
    override fun open(): RawSource = Buffer().also { it.write(bytes) }
}

/** StorageService.put に渡すオブジェクトメタ。 */
public data class ObjectMetadata(
    public val contentType: String,
    public val byteSize: Long,
    public val checksum: String,
)

/** KeyGenerator に渡す生成コンテキスト。 */
public data class KeyContext(
    public val filename: String,
    public val contentType: String,
    public val record: RecordRef,
)
```

- [ ] **Step 4: テスト成功を確認**

Run: `./gradlew :core:test --tests "*ContentSourceTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): add ContentSource, ObjectMetadata, KeyContext" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: core ポート定義

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Ports.kt`

（純粋インターフェースのため振る舞いテストなし。コンパイルで担保。）

- [ ] **Step 1: ポートを定義**

Create `Ports.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlin.time.Duration
import kotlin.time.Instant

/** 保存先抽象。キーは生成も導出もしない dumb な口。 */
public interface StorageService {
    public val name: String
    public suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata)
    public suspend fun get(key: String): RawSource
    public suspend fun exists(key: String): Boolean
    public suspend fun delete(key: String)

    /** presigned GET URL。非対応（fs 等）は null。 */
    public suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl?
}

/** メタデータ永続化（最小操作）。 */
public interface MetadataStore {
    public suspend fun insertBlob(blob: Blob)
    public suspend fun findBlob(id: BlobId): Blob?
    public suspend fun deleteBlob(id: BlobId)
    public suspend fun insertAttachment(attachment: Attachment)
    public suspend fun findAttachments(record: RecordRef, name: String): List<Attachment>
    public suspend fun deleteAttachment(id: AttachmentId)
}

/** ストレージキー生成ストラテジ。 */
public fun interface KeyGenerator {
    public fun generate(context: KeyContext): String
}

/** 配信参照の署名・検証。 */
public interface ReferenceSigner {
    public fun sign(blobId: BlobId, expiresAt: Instant): String
    public fun verify(token: String): BlobId?
}
```

- [ ] **Step 2: コンパイル確認**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(core): define ports (StorageService, MetadataStore, KeyGenerator, ReferenceSigner)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: RandomTokenKeyGenerator（既定キー生成）

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/RandomTokenKeyGenerator.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/RandomTokenKeyGeneratorTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RandomTokenKeyGeneratorTest {
    private val ctx = KeyContext("a.png", "image/png", RecordRef("User", "1"))

    @Test
    fun `generates url-safe opaque tokens`() {
        val key = RandomTokenKeyGenerator().generate(ctx)
        assertTrue(key.isNotBlank())
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "url-safe: $key")
    }

    @Test
    fun `generates distinct keys`() {
        val gen = RandomTokenKeyGenerator()
        assertNotEquals(gen.generate(ctx), gen.generate(ctx))
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*RandomTokenKeyGeneratorTest"`
Expected: FAIL（未定義）

- [ ] **Step 3: 実装**

Create `RandomTokenKeyGenerator.kt`:

```kotlin
package net.brightroom.aktivestorage

import java.security.SecureRandom
import java.util.Base64

/** 不透明・推測不能なランダムトークンを既定キーとする。 */
public class RandomTokenKeyGenerator(
    private val byteLength: Int = 20,
) : KeyGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun generate(context: KeyContext): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}
```

- [ ] **Step 4: テスト成功を確認**

Run: `./gradlew :core:test --tests "*RandomTokenKeyGeneratorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): add RandomTokenKeyGenerator" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: HmacReferenceSigner（既定署名）

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/HmacReferenceSigner.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/HmacReferenceSignerTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class HmacReferenceSignerTest {
    private val key = "test-secret".encodeToByteArray()
    private val now = Instant.fromEpochMilliseconds(1_000_000)
    private val fixedClock = object : Clock { override fun now(): Instant = now }

    @Test
    fun `sign then verify round-trips the blob id`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now + 1.hours)
        assertEquals(BlobId("b1"), signer.verify(token))
    }

    @Test
    fun `expired token verifies to null`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now - 1.hours)
        assertNull(signer.verify(token))
    }

    @Test
    fun `tampered token verifies to null`() {
        val signer = HmacReferenceSigner(key, fixedClock)
        val token = signer.sign(BlobId("b1"), now + 1.hours)
        assertNull(signer.verify(token + "x"))
    }

    @Test
    fun `wrong key verifies to null`() {
        val token = HmacReferenceSigner(key, fixedClock).sign(BlobId("b1"), now + 1.hours)
        assertNull(HmacReferenceSigner("other".encodeToByteArray(), fixedClock).verify(token))
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*HmacReferenceSignerTest"`
Expected: FAIL（未定義）

- [ ] **Step 3: 実装**

Create `HmacReferenceSigner.kt`:

```kotlin
package net.brightroom.aktivestorage

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Instant

/** HMAC-SHA256 で BlobId+失効時刻を署名した不透明トークンを発行・検証する。 */
public class HmacReferenceSigner(
    secretKey: ByteArray,
    private val clock: Clock = Clock.System,
) : ReferenceSigner {
    private val keySpec = SecretKeySpec(secretKey, ALGORITHM)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun sign(blobId: BlobId, expiresAt: Instant): String {
        val payload = "${blobId.value}:${expiresAt.toEpochMilliseconds()}"
        val payloadB64 = encoder.encodeToString(payload.encodeToByteArray())
        val mac = encoder.encodeToString(hmac(payloadB64))
        return "$payloadB64.$mac"
    }

    override fun verify(token: String): BlobId? {
        val parts = token.split(".")
        if (parts.size != 2) return null
        val (payloadB64, macB64) = parts
        val expectedMac = hmac(payloadB64)
        val actualMac = runCatching { decoder.decode(macB64) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expectedMac, actualMac)) return null

        val payload = runCatching { decoder.decode(payloadB64).decodeToString() }.getOrNull() ?: return null
        val sep = payload.lastIndexOf(':')
        if (sep < 0) return null
        val expiresAt = payload.substring(sep + 1).toLongOrNull() ?: return null
        if (expiresAt <= clock.now().toEpochMilliseconds()) return null
        return BlobId(payload.substring(0, sep))
    }

    private fun hmac(data: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(keySpec) }.doFinal(data.encodeToByteArray())

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
```

- [ ] **Step 4: テスト成功を確認**

Run: `./gradlew :core:test --tests "*HmacReferenceSignerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): add HmacReferenceSigner" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: core テスト用フェイク（InMemory 実装）

**Files:**
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryMetadataStore.kt`
- Create: `core/src/test/kotlin/net/brightroom/aktivestorage/fakes/InMemoryStorageService.kt`

（facade の縦貫通テストに使うフェイク。`src/test` に置く。）

- [ ] **Step 1: InMemoryMetadataStore を実装**

Create `InMemoryMetadataStore.kt`:

```kotlin
package net.brightroom.aktivestorage.fakes

import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef

class InMemoryMetadataStore : MetadataStore {
    val blobs = mutableMapOf<String, Blob>()
    val attachments = mutableMapOf<String, Attachment>()

    override suspend fun insertBlob(blob: Blob) { blobs[blob.id.value] = blob }
    override suspend fun findBlob(id: BlobId): Blob? = blobs[id.value]
    override suspend fun deleteBlob(id: BlobId) { blobs.remove(id.value) }
    override suspend fun insertAttachment(attachment: Attachment) { attachments[attachment.id.value] = attachment }
    override suspend fun findAttachments(record: RecordRef, name: String): List<Attachment> =
        attachments.values.filter { it.record == record && it.name == name }
    override suspend fun deleteAttachment(id: AttachmentId) { attachments.remove(id.value) }
}
```

- [ ] **Step 2: InMemoryStorageService を実装**

Create `InMemoryStorageService.kt`:

```kotlin
package net.brightroom.aktivestorage.fakes

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import kotlin.time.Duration

/** presignedGetUrl を null/非null で切り替えられるフェイク。 */
class InMemoryStorageService(
    override val name: String = "memory",
    private val presignSupported: Boolean = false,
) : StorageService {
    val objects = mutableMapOf<String, ByteArray>()

    override suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata) {
        objects[key] = content.open().use { it.buffered().readByteArray() }
    }
    override suspend fun get(key: String): RawSource =
        Buffer().also { it.write(objects.getValue(key)) }
    override suspend fun exists(key: String): Boolean = objects.containsKey(key)
    override suspend fun delete(key: String) { objects.remove(key) }
    override suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl? =
        if (presignSupported) PresignedUrl("https://example.test/$key") else null
}
```

- [ ] **Step 3: コンパイル確認**

Run: `./gradlew :core:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(core): add in-memory fakes for storage and metadata" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: AktiveStorage.attach（スプール + 整合性順序）★ウォーキングスケルトン縦貫通

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Spool.kt`
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/AttachTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AttachTest {
    private fun storage(
        service: InMemoryStorageService = InMemoryStorageService(),
        metadata: InMemoryMetadataStore = InMemoryMetadataStore(),
    ) = AktiveStorage(
        service = service,
        metadata = metadata,
        signer = HmacReferenceSigner("k".encodeToByteArray()),
    )

    @Test
    fun `attach stores bytes and persists blob and attachment`() = runTest {
        val service = InMemoryStorageService()
        val metadata = InMemoryMetadataStore()
        val sut = storage(service, metadata)

        val att = sut.attach(
            record = RecordRef("User", "42"),
            name = "avatar",
            content = ContentSource.ofBytes("a.png", "image/png", "PNG".encodeToByteArray()),
        )

        val blob = metadata.findBlob(att.blobId)!!
        assertEquals("a.png", blob.filename)
        assertEquals("image/png", blob.contentType)
        assertEquals(3L, blob.byteSize)
        assertEquals("memory", blob.serviceName)
        assertEquals(1, metadata.attachments.size)
        assertContentEquals("PNG".encodeToByteArray(), service.objects.getValue(blob.key))
    }

    @Test
    fun `attach computes base64 md5 checksum`() = runTest {
        val metadata = InMemoryMetadataStore()
        val sut = storage(metadata = metadata)
        val att = sut.attach(RecordRef("U", "1"), "f", ContentSource.ofBytes("a", "text/plain", "abc".encodeToByteArray()))
        // base64(md5("abc")) == "kAFQmDzST7DWlj99KOF/cg=="
        assertEquals("kAFQmDzST7DWlj99KOF/cg==", metadata.findBlob(att.blobId)!!.checksum)
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*AttachTest"`
Expected: FAIL（`AktiveStorage` 未定義）

- [ ] **Step 3: Spool ヘルパを実装**

Create `Spool.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** 一時ファイルへスプールしつつ byteSize と base64(MD5) を確定する。 */
internal class SpooledContent(
    private val tempFile: Path,
    val byteSize: Long,
    val checksumBase64: String,
    override val filename: String,
    override val contentType: String,
) : ContentSource {
    override fun open(): RawSource = SystemFileSystem.source(tempFile)
    fun cleanup() {
        SystemFileSystem.delete(tempFile, mustExist = false)
    }
}

internal fun spool(content: ContentSource): SpooledContent {
    val tempFile = Path(SystemTemporaryDirectory, "aktive-${UUID.randomUUID()}.tmp")
    val digest = MessageDigest.getInstance("MD5")
    var size = 0L
    content.open().buffered().use { source ->
        SystemFileSystem.sink(tempFile).buffered().use { sink ->
            while (true) {
                val chunk = source.readByteArray(minOf(source.remaining().coerceAtLeast(1), CHUNK))
                if (chunk.isEmpty()) break
                digest.update(chunk)
                size += chunk.size
                sink.write(chunk)
                if (source.exhausted()) break
            }
        }
    }
    val checksum = Base64.getEncoder().encodeToString(digest.digest())
    return SpooledContent(tempFile, size, checksum, content.filename, content.contentType)
}

private const val CHUNK = 64L * 1024
```

> 注: `Source.remaining()` / `exhausted()` / `readByteArray(n)` は kotlinx-io の API。実装時に 0.9.0 のシグネチャを確認し、必要なら `readAtMostTo(Buffer, n)` ループへ置換する（挙動: ソースを使い切るまで読み、digest 更新・サイズ加算・sink へ書き出し）。

- [ ] **Step 4: AktiveStorage facade を実装（attach のみ）**

Create `AktiveStorage.kt`:

```kotlin
package net.brightroom.aktivestorage

import java.util.UUID
import kotlin.time.Clock

public class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val clock: Clock = Clock.System,
) {
    /** 添付を作成する。順序: スプール→Blob行→実体put→Attachment行（構想 5.4）。 */
    public suspend fun attach(record: RecordRef, name: String, content: ContentSource): Attachment {
        val spooled = spool(content)
        try {
            val key = keyGenerator.generate(KeyContext(spooled.filename, spooled.contentType, record))
            val blob = Blob(
                id = BlobId(UUID.randomUUID().toString()),
                key = key,
                filename = spooled.filename,
                contentType = spooled.contentType,
                byteSize = spooled.byteSize,
                checksum = spooled.checksumBase64,
                serviceName = service.name,
                createdAt = clock.now(),
            )
            metadata.insertBlob(blob)
            service.put(key, spooled, ObjectMetadata(blob.contentType, blob.byteSize, blob.checksum))
            val attachment = Attachment(
                id = AttachmentId(UUID.randomUUID().toString()),
                name = name,
                record = record,
                blobId = blob.id,
                createdAt = clock.now(),
            )
            metadata.insertAttachment(attachment)
            return attachment
        } finally {
            spooled.cleanup()
        }
    }
}
```

- [ ] **Step 5: テスト成功を確認**

Run: `./gradlew :core:test --tests "*AttachTest"`
Expected: PASS（checksum 期待値が合わない場合は spool の読み出しロジックを修正）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core): AktiveStorage.attach with spooling and ordered persistence" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: AktiveStorage.attachments / detach

**Files:**
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/AttachmentsAndDetachTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AttachmentsAndDetachTest {
    private val record = RecordRef("User", "42")
    private fun sut(s: InMemoryStorageService, m: InMemoryMetadataStore) =
        AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    @Test
    fun `attachments lists by record and name`() = runTest {
        val m = InMemoryMetadataStore(); val s = InMemoryStorageService()
        val st = sut(s, m)
        st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
        assertEquals(1, st.attachments(record, "avatar").size)
        assertEquals(0, st.attachments(record, "documents").size)
    }

    @Test
    fun `detach with purge removes attachment, blob and object`() = runTest {
        val m = InMemoryMetadataStore(); val s = InMemoryStorageService()
        val st = sut(s, m)
        val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
        val blob = m.findBlob(att.blobId)!!

        st.detach(att, purgeBlob = true)

        assertEquals(0, m.attachments.size)
        assertNull(m.findBlob(att.blobId))
        assertFalse(s.exists(blob.key))
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*AttachmentsAndDetachTest"`
Expected: FAIL（`attachments` / `detach` 未定義）

- [ ] **Step 3: facade にメソッド追加**

Add to `AktiveStorage.kt`（class 内、attach の下）:

```kotlin
    public suspend fun attachments(record: RecordRef, name: String): List<Attachment> =
        metadata.findAttachments(record, name)

    /**
     * 添付を外す。purgeBlob=true で Blob 行と実体も削除する。
     * 注: MVP は参照カウントしない（共有 Blob の安全な回収はフェーズ2）。
     */
    public suspend fun detach(attachment: Attachment, purgeBlob: Boolean = true) {
        metadata.deleteAttachment(attachment.id)
        if (!purgeBlob) return
        val blob = metadata.findBlob(attachment.blobId) ?: return
        metadata.deleteBlob(blob.id)
        service.delete(blob.key)
    }
```

- [ ] **Step 4: テスト成功を確認**

Run: `./gradlew :core:test --tests "*AttachmentsAndDetachTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): add attachments() and detach()" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 配信（Delivery / signedReference / resolveForDelivery）

**Files:**
- Create: `core/src/main/kotlin/net/brightroom/aktivestorage/Delivery.kt`
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/DeliveryTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class DeliveryTest {
    private val record = RecordRef("User", "42")
    private fun sut(s: InMemoryStorageService, m: InMemoryMetadataStore) =
        AktiveStorage(s, m, HmacReferenceSigner("k".encodeToByteArray()))

    @Test
    fun `presign-capable service yields Redirect`() = runTest {
        val s = InMemoryStorageService(presignSupported = true); val m = InMemoryMetadataStore()
        val st = sut(s, m)
        val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
        val token = st.signedReference(m.findBlob(att.blobId)!!, 5.minutes)
        assertIs<Delivery.Redirect>(st.resolveForDelivery(token))
    }

    @Test
    fun `non-presign service yields Proxy with bytes`() = runTest {
        val s = InMemoryStorageService(presignSupported = false); val m = InMemoryMetadataStore()
        val st = sut(s, m)
        val att = st.attach(record, "avatar", ContentSource.ofBytes("a", "text/plain", "data".encodeToByteArray()))
        val token = st.signedReference(m.findBlob(att.blobId)!!, 5.minutes)
        val delivery = st.resolveForDelivery(token)
        val proxy = assertIs<Delivery.Proxy>(delivery)
        assertContentEquals("data".encodeToByteArray(), proxy.stream.buffered().readByteArray())
    }

    @Test
    fun `invalid token yields null`() = runTest {
        val st = sut(InMemoryStorageService(), InMemoryMetadataStore())
        assertNull(st.resolveForDelivery("garbage"))
    }
}
```

- [ ] **Step 2: テスト失敗を確認**

Run: `./gradlew :core:test --tests "*DeliveryTest"`
Expected: FAIL（`Delivery` / `signedReference` / `resolveForDelivery` 未定義）

- [ ] **Step 3: Delivery を定義**

Create `Delivery.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.io.RawSource

/** 配信方法。Web 層がこれを 302 / stream に振り分ける。 */
public sealed interface Delivery {
    public data class Redirect(public val url: PresignedUrl) : Delivery
    public data class Proxy(public val blob: Blob, public val stream: RawSource) : Delivery
}
```

- [ ] **Step 4: facade に配信 API を追加**

Add to `AktiveStorage.kt`（import に `import kotlin.time.Duration` と `import kotlin.time.Duration.Companion.seconds` を追加。class 内に追記）:

```kotlin
    /** 配信用の署名参照トークンを発行する。 */
    public fun signedReference(blob: Blob, ttl: Duration): String =
        signer.sign(blob.id, clock.now() + ttl)

    /**
     * トークンを検証し配信方法を返す。
     * presigned 対応サービスは Redirect、非対応(fs)は Proxy へ自動フォールバック。
     */
    public suspend fun resolveForDelivery(token: String, redirectTtl: Duration = 30.seconds): Delivery? {
        val blobId = signer.verify(token) ?: return null
        val blob = metadata.findBlob(blobId) ?: return null
        val url = service.presignedGetUrl(blob.key, redirectTtl)
        return if (url != null) Delivery.Redirect(url) else Delivery.Proxy(blob, service.get(blob.key))
    }
```

- [ ] **Step 5: テスト成功を確認**

Run: `./gradlew :core:test`
Expected: PASS（core 全体）

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core): add delivery resolution (Redirect/Proxy) and signed references" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: storage-fs アダプタ

**Files:**
- Create: `storage-fs/build.gradle.kts`
- Create: `storage-fs/src/main/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageService.kt`
- Test: `storage-fs/src/test/kotlin/net/brightroom/aktivestorage/storage/fs/FilesystemStorageServiceTest.kt`
- Modify: `settings.gradle.kts`（`include("storage-fs")`）

- [ ] **Step 1: モジュール登録 + build.gradle.kts**

Edit `settings.gradle.kts`: 末尾に `include("storage-fs")` を追加。

Create `storage-fs/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 2: 失敗するテストを書く**

```kotlin
package net.brightroom.aktivestorage.storage.fs

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FilesystemStorageServiceTest {
    private fun service(dir: java.io.File) = FilesystemStorageService(Path(dir.absolutePath))
    private fun bytes(b: String) = ObjectMetadata("text/plain", b.length.toLong(), "chk")

    @Test
    fun `put then get round-trips bytes`(@TempDir dir: java.io.File) = runTest {
        val s = service(dir)
        s.put("a/b/key1", ContentSource.ofBytes("f", "text/plain", "hello".encodeToByteArray()), bytes("hello"))
        assertTrue(s.exists("a/b/key1"))
        assertContentEquals("hello".encodeToByteArray(), s.get("a/b/key1").buffered().readByteArray())
    }

    @Test
    fun `delete removes object`(@TempDir dir: java.io.File) = runTest {
        val s = service(dir)
        s.put("k", ContentSource.ofBytes("f", "text/plain", "x".encodeToByteArray()), bytes("x"))
        s.delete("k")
        assertFalse(s.exists("k"))
    }

    @Test
    fun `presignedGetUrl is null for fs`(@TempDir dir: java.io.File) = runTest {
        assertNull(service(dir).presignedGetUrl("k", 5.minutes))
    }

    @Test
    fun `rejects path traversal keys`(@TempDir dir: java.io.File) = runTest {
        assertFailsWith<IllegalArgumentException> {
            service(dir).put("../escape", ContentSource.ofBytes("f", "text/plain", "x".encodeToByteArray()), bytes("x"))
        }
    }
}
```

- [ ] **Step 3: テスト失敗を確認**

Run: `./gradlew :storage-fs:test`
Expected: FAIL（`FilesystemStorageService` 未定義）

- [ ] **Step 4: 実装**

Create `FilesystemStorageService.kt`:

```kotlin
package net.brightroom.aktivestorage.storage.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.ObjectMetadata
import net.brightroom.aktivestorage.PresignedUrl
import net.brightroom.aktivestorage.StorageService
import java.util.UUID
import kotlin.time.Duration

/** ローカルFSアダプタ。presigned 非対応（get で proxy 配信される）。 */
public class FilesystemStorageService(
    private val root: Path,
    override val name: String = "fs",
) : StorageService {

    override suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata): Unit =
        withContext(Dispatchers.IO) {
            val target = resolveSafe(key)
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            val tmp = Path(target.parent.toString(), "${target.name}.tmp-${UUID.randomUUID()}")
            content.open().use { src ->
                SystemFileSystem.sink(tmp).buffered().use { sink -> sink.transferFrom(src) }
            }
            SystemFileSystem.atomicMove(tmp, target)
        }

    override suspend fun get(key: String): RawSource =
        withContext(Dispatchers.IO) { SystemFileSystem.source(resolveSafe(key)) }

    override suspend fun exists(key: String): Boolean =
        withContext(Dispatchers.IO) { SystemFileSystem.exists(resolveSafe(key)) }

    override suspend fun delete(key: String): Unit =
        withContext(Dispatchers.IO) { SystemFileSystem.delete(resolveSafe(key), mustExist = false) }

    override suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl? = null

    private fun resolveSafe(key: String): Path {
        val parts = key.split('/')
        require(key.isNotBlank() && parts.none { it == ".." || it == "." || it.isEmpty() }) {
            "invalid storage key: $key"
        }
        return Path(root.toString(), *parts.toTypedArray())
    }
}
```

> 注: `Sink.transferFrom(RawSource)` / `SystemFileSystem.atomicMove` は kotlinx-io 0.9.0 の API。シグネチャを確認し、必要なら `readByteArray` ループへ置換。

- [ ] **Step 5: テスト成功を確認**

Run: `./gradlew :storage-fs:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(storage-fs): add FilesystemStorageService" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: storage-s3 アダプタ（Testcontainers MinIO）

**Files:**
- Create: `storage-s3/build.gradle.kts`
- Create: `storage-s3/src/main/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageService.kt`
- Test: `storage-s3/src/test/kotlin/net/brightroom/aktivestorage/storage/s3/S3StorageServiceIT.kt`
- Modify: `settings.gradle.kts`（`include("storage-s3")`）

- [ ] **Step 1: モジュール登録 + build.gradle.kts**

Edit `settings.gradle.kts`: `include("storage-s3")` を追加。

Create `storage-s3/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
}

dependencies {
    api(project(":core"))
    implementation(libs.aws.s3)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}
```

> `aktive.integration-test` 規約は Task 15-前提として Task 11 着手時に作成する（下記 Step 2）。

- [ ] **Step 2: integration-test 規約プラグインを作成**

Create `build-logic/src/main/kotlin/aktive.integration-test.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs @Tag(\"integration\") tests."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") { dependsOn(integrationTest) }
```

- [ ] **Step 3: 失敗する統合テストを書く**

```kotlin
package net.brightroom.aktivestorage.storage.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
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
    fun setup() = runBlocking {
        minio = MinIOContainer("minio/minio").also { it.start() }
        client = S3Client {
            region = "us-east-1"
            endpointUrl = Url.parse(minio.s3URL)
            forcePathStyle = true
            credentialsProvider = StaticCredentialsProvider(
                Credentials(minio.userName, minio.password),
            )
        }
        client.createBucket { this.bucket = this@S3StorageServiceIT.bucket }
    }

    @AfterAll
    fun teardown() {
        client.close()
        minio.stop()
    }

    private fun service() = S3StorageService(client, bucket)
    private fun meta(b: String) = ObjectMetadata("text/plain", b.length.toLong(), "chk")

    @Test
    fun `put get exists delete round-trip`() = runBlocking {
        val s = service()
        s.put("k1", ContentSource.ofBytes("f", "text/plain", "hello".encodeToByteArray()), meta("hello"))
        assertTrue(s.exists("k1"))
        assertContentEquals("hello".encodeToByteArray(), s.get("k1").buffered().readByteArray())
        s.delete("k1")
        assertFalse(s.exists("k1"))
    }

    @Test
    fun `presignedGetUrl returns a fetchable url`() = runBlocking {
        val s = service()
        s.put("k2", ContentSource.ofBytes("f", "text/plain", "data".encodeToByteArray()), meta("data"))
        val url = s.presignedGetUrl("k2", 5.minutes)!!
        val fetched = java.net.URI.create(url.value).toURL().readBytes()
        assertContentEquals("data".encodeToByteArray(), fetched)
    }
}
```

- [ ] **Step 4: テスト失敗を確認**

Run: `./gradlew :storage-s3:integrationTest`
Expected: FAIL（`S3StorageService` 未定義）

- [ ] **Step 5: 実装**

Create `S3StorageService.kt`:

```kotlin
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

    override suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata) {
        val bytes = content.open().buffered().use { it.readByteArray() } // MVP: 全読み（multipart はフェーズ2）
        client.putObject {
            this.bucket = this@S3StorageService.bucket
            this.key = key
            this.contentType = meta.contentType
            this.contentLength = meta.byteSize
            this.body = ByteStream.fromBytes(bytes)
        }
    }

    override suspend fun get(key: String): RawSource {
        val bytes = client.getObject(
            GetObjectRequest {
                this.bucket = this@S3StorageService.bucket
                this.key = key
            },
        ) { resp -> resp.body?.toByteArray() ?: ByteArray(0) }
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

    override suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl {
        val presigned = client.presignGetObject(
            GetObjectRequest {
                this.bucket = this@S3StorageService.bucket
                this.key = key
            },
            ttl,
        )
        return PresignedUrl(presigned.url.toString())
    }
}
```

> 注: AWS SDK for Kotlin の正確なシンボル（`presignGetObject` の引数、`resp.body?.toByteArray()`、`NotFound`）は 1.6.91 で確認する。getObject はラムダスコープ内でストリームを読み切ること（スコープ外はクローズされる）。

- [ ] **Step 6: テスト成功を確認**

Run: `./gradlew :storage-s3:integrationTest`（Docker 必須）
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(storage-s3): add S3StorageService with MinIO integration test" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: metadata-exposed-jdbc アダプタ（Testcontainers Postgres）

**Files:**
- Create: `metadata-exposed-jdbc/build.gradle.kts`
- Create: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/Tables.kt`
- Create: `metadata-exposed-jdbc/src/main/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStore.kt`
- Test: `metadata-exposed-jdbc/src/test/kotlin/net/brightroom/aktivestorage/metadata/exposed/ExposedMetadataStoreIT.kt`
- Modify: `settings.gradle.kts`（`include("metadata-exposed-jdbc")`）

- [ ] **Step 1: モジュール登録 + build.gradle.kts**

Edit `settings.gradle.kts`: `include("metadata-exposed-jdbc")` を追加。

Create `metadata-exposed-jdbc/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
}

dependencies {
    api(project(":core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
```

- [ ] **Step 2: 失敗する統合テストを書く**

```kotlin
package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.sql.Database
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

    private fun blob(id: String, key: String) = Blob(
        BlobId(id), key, "a.png", "image/png", 3, "chk", "s3", Instant.fromEpochMilliseconds(0),
    )

    @BeforeAll
    fun setup() {
        pg = PostgreSQLContainer("postgres:17").also { it.start() }
        val db = Database.connect(pg.jdbcUrl, user = pg.username, password = pg.password)
        store = ExposedMetadataStore(db).also { it.createSchema() }
    }

    @AfterAll
    fun teardown() { pg.stop() }

    @Test
    fun `insert and find blob`() = runBlocking {
        store.insertBlob(blob("b1", "k1"))
        assertEquals("k1", store.findBlob(BlobId("b1"))!!.key)
        store.deleteBlob(BlobId("b1"))
        assertNull(store.findBlob(BlobId("b1")))
    }

    @Test
    fun `find attachments by record and name`() = runBlocking {
        store.insertBlob(blob("b2", "k2"))
        val record = RecordRef("User", "42")
        store.insertAttachment(Attachment(AttachmentId("a1"), "avatar", record, BlobId("b2"), Instant.fromEpochMilliseconds(0)))
        assertEquals(1, store.findAttachments(record, "avatar").size)
        assertEquals(0, store.findAttachments(record, "documents").size)
        store.deleteAttachment(AttachmentId("a1"))
        assertEquals(0, store.findAttachments(record, "avatar").size)
    }
}
```

- [ ] **Step 3: テスト失敗を確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`
Expected: FAIL（`ExposedMetadataStore` 未定義）

- [ ] **Step 4: テーブル定義**

Create `Tables.kt`:

```kotlin
package net.brightroom.aktivestorage.metadata.exposed

import org.jetbrains.exposed.sql.Table

// created_at は epoch millis（BIGINT）として保持し、kotlin.time.Instant と相互変換する。
internal object BlobsTable : Table("aktive_blobs") {
    val id = varchar("id", 64)
    val key = varchar("key", 512).uniqueIndex()
    val filename = varchar("filename", 1024)
    val contentType = varchar("content_type", 255)
    val byteSize = long("byte_size")
    val checksum = varchar("checksum", 128)
    val serviceName = varchar("service_name", 64)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object AttachmentsTable : Table("aktive_attachments") {
    val id = varchar("id", 64)
    val name = varchar("name", 255)
    val recordType = varchar("record_type", 255)
    val recordId = varchar("record_id", 255)
    val blobId = varchar("blob_id", 64).references(BlobsTable.id)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, recordType, recordId, name)
    }
}
```

- [ ] **Step 5: ExposedMetadataStore を実装**

Create `ExposedMetadataStore.kt`:

```kotlin
package net.brightroom.aktivestorage.metadata.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.brightroom.aktivestorage.Attachment
import net.brightroom.aktivestorage.AttachmentId
import net.brightroom.aktivestorage.Blob
import net.brightroom.aktivestorage.BlobId
import net.brightroom.aktivestorage.MetadataStore
import net.brightroom.aktivestorage.RecordRef
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Instant

public class ExposedMetadataStore(private val db: Database) : MetadataStore {

    /** テスト/開発用のスキーマ作成。本番は各自のマイグレーションで管理する。 */
    public fun createSchema() {
        transaction(db) { SchemaUtils.create(BlobsTable, AttachmentsTable) }
    }

    override suspend fun insertBlob(blob: Blob): Unit = dbQuery {
        BlobsTable.insert {
            it[id] = blob.id.value
            it[key] = blob.key
            it[filename] = blob.filename
            it[contentType] = blob.contentType
            it[byteSize] = blob.byteSize
            it[checksum] = blob.checksum
            it[serviceName] = blob.serviceName
            it[createdAt] = blob.createdAt.toEpochMilliseconds()
        }
        Unit
    }

    override suspend fun findBlob(id: BlobId): Blob? = dbQuery {
        BlobsTable.selectAll().where { BlobsTable.id eq id.value }.singleOrNull()?.toBlob()
    }

    override suspend fun deleteBlob(id: BlobId): Unit = dbQuery {
        BlobsTable.deleteWhere { BlobsTable.id eq id.value }
        Unit
    }

    override suspend fun insertAttachment(attachment: Attachment): Unit = dbQuery {
        AttachmentsTable.insert {
            it[id] = attachment.id.value
            it[name] = attachment.name
            it[recordType] = attachment.record.type
            it[recordId] = attachment.record.id
            it[blobId] = attachment.blobId.value
            it[createdAt] = attachment.createdAt.toEpochMilliseconds()
        }
        Unit
    }

    override suspend fun findAttachments(record: RecordRef, name: String): List<Attachment> = dbQuery {
        AttachmentsTable.selectAll().where {
            (AttachmentsTable.recordType eq record.type) and
                (AttachmentsTable.recordId eq record.id) and
                (AttachmentsTable.name eq name)
        }.map { it.toAttachment() }
    }

    override suspend fun deleteAttachment(id: AttachmentId): Unit = dbQuery {
        AttachmentsTable.deleteWhere { AttachmentsTable.id eq id.value }
        Unit
    }

    private suspend fun <T> dbQuery(block: () -> T): T =
        withContext(Dispatchers.IO) { transaction(db) { block() } }

    private fun ResultRow.toBlob() = Blob(
        id = BlobId(this[BlobsTable.id]),
        key = this[BlobsTable.key],
        filename = this[BlobsTable.filename],
        contentType = this[BlobsTable.contentType],
        byteSize = this[BlobsTable.byteSize],
        checksum = this[BlobsTable.checksum],
        serviceName = this[BlobsTable.serviceName],
        createdAt = Instant.fromEpochMilliseconds(this[BlobsTable.createdAt]),
    )

    private fun ResultRow.toAttachment() = Attachment(
        id = AttachmentId(this[AttachmentsTable.id]),
        name = this[AttachmentsTable.name],
        record = RecordRef(this[AttachmentsTable.recordType], this[AttachmentsTable.recordId]),
        blobId = BlobId(this[AttachmentsTable.blobId]),
        createdAt = Instant.fromEpochMilliseconds(this[AttachmentsTable.createdAt]),
    )
}
```

> 注: **Exposed 1.0 はパッケージを再編済み**。`org.jetbrains.exposed.sql.*` / `transactions.transaction` / `selectAll().where{}` / `deleteWhere{}` の import とシグネチャを 1.3.0 のドキュメントで確認し、必要に応じて修正する（DSL の形は不変）。

- [ ] **Step 6: テスト成功を確認**

Run: `./gradlew :metadata-exposed-jdbc:integrationTest`（Docker 必須）
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(metadata-exposed-jdbc): add ExposedMetadataStore with Postgres integration test" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: integration-tests（E2E・完了判定 #1）

**Files:**
- Create: `integration-tests/build.gradle.kts`
- Modify: `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`（`blobOf` 追加）
- Test: `core/src/test/kotlin/net/brightroom/aktivestorage/BlobOfTest.kt`
- Test: `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt`
- Modify: `settings.gradle.kts`（`include("integration-tests")`）

- [ ] **Step 1: モジュール登録 + build.gradle.kts**

Edit `settings.gradle.kts`: `include("integration-tests")` を追加。

Create `integration-tests/build.gradle.kts`:

```kotlin
plugins {
    id("aktive.kotlin-library")
    id("aktive.integration-test")
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":storage-s3"))
    testImplementation(project(":metadata-exposed-jdbc"))
    testImplementation(libs.aws.s3)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.postgresql.driver)
}
```

- [ ] **Step 2: core に blobOf を追加（E2E が必要とする）**

`signedReference(blob, ttl)` は `Blob` を要求する。添付から Blob を引く小さな公開メソッドを facade に足す。

Add to `core/src/main/kotlin/net/brightroom/aktivestorage/AktiveStorage.kt`（class 内）:

```kotlin
    /** 添付に対応する Blob を引く。 */
    public suspend fun blobOf(attachment: Attachment): Blob? = metadata.findBlob(attachment.blobId)
```

Create `core/src/test/kotlin/net/brightroom/aktivestorage/BlobOfTest.kt`:

```kotlin
package net.brightroom.aktivestorage

import kotlinx.coroutines.test.runTest
import net.brightroom.aktivestorage.fakes.InMemoryMetadataStore
import net.brightroom.aktivestorage.fakes.InMemoryStorageService
import kotlin.test.Test
import kotlin.test.assertEquals

class BlobOfTest {
    @Test
    fun `blobOf returns the blob for an attachment`() = runTest {
        val m = InMemoryMetadataStore()
        val st = AktiveStorage(InMemoryStorageService(), m, HmacReferenceSigner("k".encodeToByteArray()))
        val att = st.attach(RecordRef("U", "1"), "avatar", ContentSource.ofBytes("a", "text/plain", "x".encodeToByteArray()))
        assertEquals(att.blobId, st.blobOf(att)!!.id)
    }
}
```

Run: `./gradlew :core:test --tests "*BlobOfTest"`
Expected: PASS

- [ ] **Step 3: E2E テストを書く**

Create `integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt`:

```kotlin
package net.brightroom.aktivestorage.it

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.createBucket
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
import net.brightroom.aktivestorage.AktiveStorage
import net.brightroom.aktivestorage.ContentSource
import net.brightroom.aktivestorage.Delivery
import net.brightroom.aktivestorage.HmacReferenceSigner
import net.brightroom.aktivestorage.RecordRef
import net.brightroom.aktivestorage.metadata.exposed.ExposedMetadataStore
import net.brightroom.aktivestorage.storage.s3.S3StorageService
import org.jetbrains.exposed.sql.Database
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
    fun setup() = runBlocking {
        minio = MinIOContainer("minio/minio").also { it.start() }
        pg = PostgreSQLContainer("postgres:17").also { it.start() }
        client = S3Client {
            region = "us-east-1"
            endpointUrl = Url.parse(minio.s3URL)
            forcePathStyle = true
            credentialsProvider = StaticCredentialsProvider(Credentials(minio.userName, minio.password))
        }
        client.createBucket { bucket = "e2e" }
        val metadata = ExposedMetadataStore(
            Database.connect(pg.jdbcUrl, user = pg.username, password = pg.password),
        ).also { it.createSchema() }
        storage = AktiveStorage(
            service = S3StorageService(client, "e2e"),
            metadata = metadata,
            signer = HmacReferenceSigner("e2e-secret".encodeToByteArray()),
        )
    }

    @AfterAll
    fun teardown() {
        client.close(); minio.stop(); pg.stop()
    }

    @Test
    fun `attach persists, stores, and serves via signed redirect`() = runBlocking {
        val payload = "the-bytes".encodeToByteArray()
        val record = RecordRef("User", "42")

        val att = storage.attach(record, "avatar", ContentSource.ofBytes("a.png", "image/png", payload))

        // メタデータに永続化されている
        assertContentEquals(
            listOf(att.blobId),
            storage.attachments(record, "avatar").map { it.blobId },
        )

        // 署名トークン → Redirect(presigned) → 実体が一致
        val blob = storage.blobOf(att)!!
        val token = storage.signedReference(blob, 5.minutes)
        val delivery = storage.resolveForDelivery(token)
        val redirect = assertIs<Delivery.Redirect>(delivery)
        val fetched = java.net.URI.create(redirect.url.value).toURL().readBytes()
        assertContentEquals(payload, fetched)
    }
}
```

- [ ] **Step 4: E2E を実行（完了判定 #1）**

Run: `./gradlew :integration-tests:integrationTest`（Docker 必須）
Expected: PASS。添付→Postgres永続化→MinIO保存→署名トークン→presigned redirect→バイト一致が通る。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(integration): add end-to-end attach→store→signed-redirect→fetch" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: bom モジュール

**Files:**
- Create: `bom/build.gradle.kts`
- Modify: `settings.gradle.kts`（`include("bom")`）

- [ ] **Step 1: bom を作成**

Edit `settings.gradle.kts`: `include("bom")` を追加。

Create `bom/build.gradle.kts`:

```kotlin
plugins {
    `java-platform`
    id("aktive.published")
}

dependencies {
    constraints {
        api(project(":core"))
        api(project(":storage-fs"))
        api(project(":storage-s3"))
        api(project(":metadata-exposed-jdbc"))
    }
}
```

> `aktive.published` は Task 15 で作成。Task 15 完了後に bom もビルド可能になる。

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "feat(bom): add version-aligning platform module" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: 公開設定（maven-publish / signing / sources / javadoc）

**Files:**
- Create: `build-logic/src/main/kotlin/aktive.published.gradle.kts`
- Modify: 各公開モジュールの `build.gradle.kts`（`id("aktive.published")` を追加し group/version 設定）
- Modify: `gradle.properties`（group/version）

- [ ] **Step 1: group/version を gradle.properties に追加**

Edit `gradle.properties` に追記:

```properties
group=net.bright-room.aktive-storage
version=0.1.0-SNAPSHOT
```

- [ ] **Step 2: published 規約プラグインを作成**

Create `build-logic/src/main/kotlin/aktive.published.gradle.kts`:

```kotlin
plugins {
    `maven-publish`
    signing
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

// Kotlin/JVM モジュールには sources + dokka javadoc jar を付ける（java-platform は除く）
plugins.withId("org.jetbrains.kotlin.jvm") {
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        withSourcesJar()
    }
    apply(plugin = "org.jetbrains.dokka-javadoc")
    val javadocJar = tasks.register<Jar>("javadocJar") {
        archiveClassifier.set("javadoc")
        // Dokka 2.x の javadoc 出力タスク名を `./gradlew tasks` で確認して接続する
        from(tasks.named("dokkaGeneratePublicationJavadoc"))
    }
    tasks.named("assemble") { dependsOn(javadocJar) }
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            val isPlatform = project.plugins.hasPlugin("java-platform")
            if (isPlatform) from(components["javaPlatform"]) else from(components["java"])
            if (!isPlatform) {
                artifact(tasks.named("javadocJar"))
            }
            pom {
                name.set(project.name)
                description.set("Framework-agnostic file attachment toolkit for the JVM (${project.name}).")
                url.set("https://github.com/bright-room/aktive-storage")
                licenses { license { name.set("Apache-2.0") } }
                developers { developer { id.set("bright-room") } }
                scm { url.set("https://github.com/bright-room/aktive-storage") }
            }
        }
    }
}

signing {
    // CI/ローカルで鍵が無い場合はスキップ（mavenLocal 検証は鍵不要）
    isRequired = false
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications)
    }
}
```

> 注: Dokka 2.2.0(v2) の javadoc タスク名は要確認（`dokkaGeneratePublicationJavadoc` が有力）。`./gradlew :core:tasks --group=documentation` で確認し接続する。

- [ ] **Step 3: 公開モジュールに規約を適用**

各 `build.gradle.kts`（`core` / `storage-fs` / `storage-s3` / `metadata-exposed-jdbc`）の `plugins {}` に追加:

```kotlin
    id("aktive.published")
```

（`bom/build.gradle.kts` は Task 14 で適用済み）

- [ ] **Step 4: 梱包検証（完了判定 #3）**

Run: `./gradlew publishToMavenLocal`
Expected: BUILD SUCCESSFUL。`~/.m2/repository/net/bright-room/aktive-storage/` 配下に各 artifact の `.jar` / `-sources.jar` / `-javadoc.jar` / `.pom` / bom の `.pom` が出力される。

Run: `ls ~/.m2/repository/net/bright-room/aktive-storage/core/0.1.0-SNAPSHOT/`
Expected: `core-0.1.0-SNAPSHOT.jar`, `-sources.jar`, `-javadoc.jar`, `.pom` を確認。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "build: add publishing (maven-publish, signing, sources/javadoc jars)" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: CI/CD と Renovate（完了判定 #4）

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/verify-packaging.yml`
- Create: `renovate.json`

- [ ] **Step 1: ci.yml を作成**

Create `.github/workflows/ci.yml`（spec 12.2 のブロック記法そのまま）:

```yaml
name: CI

on:
  pull_request:
    paths-ignore:
      - "docs/**"
      - "**.md"
  push:
    branches:
      - main
    paths-ignore:
      - "docs/**"
      - "**.md"

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  lint:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - name: Set up JDK
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: "21"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
      - name: Spotless check
        run: ./gradlew spotlessCheck

  test:
    needs: lint
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - name: Set up JDK
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: "21"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
      - name: Unit tests
        run: ./gradlew test
      - name: Upload reports on failure
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: reports-test
          path: "**/build/reports/"

  integration-test:
    needs: lint
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - name: Set up JDK
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: "21"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
      - name: Integration tests (Testcontainers)
        run: ./gradlew integrationTest
      - name: Upload reports on failure
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1
        with:
          name: reports-integration
          path: "**/build/reports/"
```

- [ ] **Step 2: verify-packaging.yml を作成**

Create `.github/workflows/verify-packaging.yml`（spec 12.3 そのまま）:

```yaml
name: Verify Packaging

on:
  workflow_run:
    workflows:
      - CI
    types:
      - completed
    branches:
      - main

permissions:
  contents: read

jobs:
  verify-packaging:
    if: ${{ github.event.workflow_run.conclusion == 'success' }}
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          ref: ${{ github.event.workflow_run.head_sha }}
          persist-credentials: false
      - name: Set up JDK
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: "21"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
      - name: Verify packaging (publish to mavenLocal)
        run: ./gradlew publishToMavenLocal
```

- [ ] **Step 3: renovate.json を作成**

Create `renovate.json`（spec 12.5）:

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended"],
  "timezone": "Asia/Tokyo",
  "schedule": ["before 9am on saturday"],
  "labels": ["Kind: Dependencies"],
  "prConcurrentLimit": 0,
  "prHourlyLimit": 0,
  "separateMinorPatch": true,
  "minimumReleaseAge": "7 days",
  "automerge": true,
  "major": { "minimumReleaseAge": "14 days", "automerge": false }
}
```

- [ ] **Step 4: ローカルで完了判定を最終確認**

Run: `./gradlew spotlessCheck test`（Docker 無しで通ること）
Expected: BUILD SUCCESSFUL

Run: `./gradlew check`（Docker ありで integrationTest 含め通ること）
Expected: BUILD SUCCESSFUL（完了判定 #1/#2）

Run: `./gradlew publishToMavenLocal`
Expected: BUILD SUCCESSFUL（完了判定 #3）

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "ci: add CI, packaging-verify workflows and Renovate config" -m "Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review（spec 突合）

- **スコープ網羅**: core(型/ポート/facade) T1–T9 / storage-fs T10 / storage-s3 T11 / metadata-exposed-jdbc T12 / bom T14 / サーバー経由アップロード=attach T7 / 取得(redirect+proxy) T9,T11 / 署名 T5,T9 / E2E T13 / CI/Renovate/Spotless T0,T16 / 公開設定 T15。spec の MVP「含む」項目はすべてタスクへ対応済み。
- **言語中立**: 公開 API は kotlinx-io(`RawSource`)/`kotlin.time`/値クラス(`PresignedUrl`)のみ。java.* は内部実装（MD5・HMAC・spool・UUID）に限定。
- **型整合**: `BlobId/AttachmentId/PresignedUrl/RecordRef/Blob/Attachment/ContentSource/ObjectMetadata/KeyContext/Delivery` と各ポートのシグネチャは T1–T3 で定義し、T7–T13 で一貫使用。`Clock`/`Instant`/`Duration` は `kotlin.time`。
- **完了判定**: #1=T13 / #2=各タスクの test / #3=T15 / #4=T16。
- **要検証ポイント（実装時に必ず確認）**: ① Kotlin 2.4.0 toolchain(T0、ダメなら 2.3.20) ② kotlinx-io 0.9.0 のストリーム API(`transferFrom`/`atomicMove`/`readByteArray`) ③ AWS SDK for Kotlin 1.6.91 のシンボル(presign/getObject/NotFound) ④ Exposed 1.3.0 のパッケージ/DSL import ⑤ Dokka 2.2.0 の javadoc タスク名。これらは「形は確定・名前のみ要確認」。

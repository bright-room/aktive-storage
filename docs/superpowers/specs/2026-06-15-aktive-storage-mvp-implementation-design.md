# 実装設計書: aktive-storage フェーズ1（MVP）

**対象**: フェーズ1（MVP）のみ
**親ドキュメント**: [構想ドキュメント](../../jvm-attachment-storage-design.md)
**日付**: 2026-06-15

本書は構想ドキュメントを「実装計画に落とせる粒度」へ具体化した実装設計書である。確定済みの設計判断・最新バージョン・モジュール構成・配信戦略・テスト戦略・CI/CD・完了判定を定める。次段は本書を元にした実装計画（writing-plans）。

---

## 1. スコープ

### MVP に含む

- `core`（ポート定義 + 高レベル添付 API）
- `storage-fs`（ローカルFSアダプタ）
- `storage-s3`（S3 / S3互換アダプタ）
- `metadata-exposed-jdbc`（Exposed/JDBC 永続化アダプタ）
- `bom`（バージョン整合用 platform）
- サーバー経由アップロード
- 取得（S3=リダイレクト / fs=最小プロキシ。下記 7 章）
- HMAC 署名付き参照（有効期限つき）
- 検証用 `integration-tests`（非公開・E2E）
- CI/CD（`ci.yml` + `verify-packaging.yml`）、Renovate、Spotless

### MVP に含まない（フェーズ2/3へ明示的に先送り）

- `integration-ktor` / `integration-spring-boot-starter`
- 画像バリアント（`variant-*`）
- ダイレクトアップロード
- リッチなプロキシモード配信（range / cache-control / CDN 連携）
- 孤立 Blob の回収（GC）、`purge_later`（非同期一括削除）、`unattached`（孤立走査）
- 業務トランザクションへの相乗り（透過的統合）
- 署名鍵ローテーション、複数ストレージ振り分けレジストリ
- GCS / Azure アダプタ、jOOQ / JPA / R2DBC アダプタ、ミラーリング
- 実 Maven Central へのリリース（設定のみ実装し、検証は `publishToMavenLocal` まで）

---

## 2. 確定した設計判断

| 論点 | 決定 | 理由 |
| --- | --- | --- |
| スコープ | フェーズ1（MVP）のみ | 中核体験を最短で成立させる縦割り1枚。以降は別 spec→plan |
| レコード参照モデル | **C案**: core は `RecordRef(type, id)` の文字列ペア。型付き糖衣は後回し（加算的に追加可能な形だけ確保） | 永続化層は ORM 非依存ゆえ文字列ペアに収束。型知識は将来 ORM アダプタ側へ置ける |
| core の型 | **言語中立**: java.* に依存せず Kotlin 多プラットフォーム中立の型で公開 API を構成 | 構想 13 章の Kotlin/Native 対応余地を最初から殺さない（YAGNI 内で達成可能） |
| テスト戦略 | レイヤード（高速ユニット + 統合のみ Testcontainers、タグ分離） | S3 アダプタは実プロトコルで検証が要る一方、ユニットは Docker 不要で高速化 |
| 統合テストのサービス調達 | Testcontainers（MinIO + Postgres） | ライブラリ向きで hermetic・IDE でそのまま動く・compose.yml 不要 |
| JDK | bytecode target **21**、CI ビルドも **21** | ライブラリ消費者の裾野を確保。下限ビルドで「コンパイルできれば 21 互換」を保証 |
| 公開 | 設定のみ実装。実 Central プッシュはスコープ外。完了判定は `publishToMavenLocal` 成功（=梱包検証） | リリース運用は別途。MVP は配布可能な形まで |
| 配信 | `Delivery` sealed 結果で redirect/proxy をストレージ能力により自動分岐 | fs は presigned 不可なので redirect-only は不成立（7 章で詳述） |
| ビルド進行 | ウォーキングスケルトン優先 | ポート抽象が composable かが最大の不確実性。最初に縦貫通で潰す |

---

## 3. ツールチェイン・バージョン（2026-06-15 実測）

| 対象 | バージョン | 備考 |
| --- | --- | --- |
| Gradle | 9.5.1 | 実行に JDK 17+。target 21 は toolchain で指定 |
| Kotlin | 2.4.0 | リリース直後のため要検証。問題時は **2.3.20 へフォールバック** |
| kotlinx-coroutines | 1.11.0 | core の suspend モデルの土台 |
| kotlinx-io | 0.9.0 | core の中立ストリーム型（`RawSource` / `Source`）。pre-1.0 のため公開 API の churn に注意 |
| Exposed | 1.3.0 | 1.x は API 安定保証。MVP は JDBC を採用（R2DBC は将来別モジュール） |
| AWS SDK for Kotlin | 1.6.91 | suspend ネイティブ。頻繁に更新されるため Renovate 追従 |
| Testcontainers | 2.0.5 | 2.x メジャー。座標・API 差分は skeleton で吸収 |
| JUnit | 6.1.0 | 6.x メジャー。JDK 17+ 必須（target 21 で問題なし） |
| Spotless | 8.6.0 | ktlint 1.8.0 を使用。ルールは `.editorconfig` |

時刻型は stdlib の `kotlin.time`（`Instant` / `Clock` / `Duration`、Kotlin 2.3+ で安定）を用い、追加依存を持たない。

**Kotlin 2.4.0 の扱い**: リリース 12 日（2026-06-03）。Gradle 9.5.1 / Dokka / KGP との組み合わせが未検証のため、ウォーキングスケルトンの最初のステップを「この toolchain で空ビルドが通るか」の検証とし、ツーリングが追いつかなければ Kotlin 2.3.20 へ即フォールバックする。

---

## 4. モジュール構成

```
aktive-storage/
├─ build-logic/              # 規約プラグイン（composite build, 非公開）。kotlin/publishing/testing/spotless 共通設定
├─ gradle/libs.versions.toml # バージョンカタログ
├─ core/                     # ポート + 添付API（公開）
├─ storage-fs/               # ローカルFSアダプタ（公開）
├─ storage-s3/               # S3アダプタ: AWS SDK for Kotlin（公開）
├─ metadata-exposed-jdbc/    # Exposed/JDBC 永続化アダプタ（公開）
├─ bom/                      # バージョン整合用 platform（公開）
└─ integration-tests/        # E2E検証（非公開・Testcontainers）
```

- 公開 artifact: `core` / `storage-fs` / `storage-s3` / `metadata-exposed-jdbc` / `bom`。座標は `net.bright-room.aktive-storage:<artifactId>`。
- 依存方向は常に各アダプタ → `core` の一方向（依存性逆転）。`core` はアダプタを知らない。
- `metadata-exposed-jdbc` の `-jdbc` 接尾辞は、将来の `metadata-exposed-r2dbc` 追加を見越したドライバ識別（現状は JDBC のみ）。後から改名する痛みを避ける。
- `integration-tests` のみ全モジュールに依存し、MVP の縦貫通を保証する。
- 規約プラグインは `build-logic`（composite build）に集約し、各モジュールの `build.gradle.kts` を最小化する。

---

## 5. core: ドメインモデル・ポート・添付API

### 5.1 ドメイン型

```kotlin
@JvmInline value class BlobId(val value: String)        // 論理ID。署名トークンに埋める対象
@JvmInline value class AttachmentId(val value: String)
@JvmInline value class PresignedUrl(val value: String)  // URL の中立表現（java.net.URI に依存しない）

data class RecordRef(val type: String, val id: String)  // C案: 文字列ペア。糖衣は後付け可能な形

data class Blob(
    val id: BlobId,
    val key: String,           // ストレージ上のオブジェクトキー（生成時確定・再導出しない）
    val filename: String,      // 元ファイル名（メタ退避）
    val contentType: String,
    val byteSize: Long,
    val checksum: String,      // 既定: base64(MD5)。S3 ETag 互換・整合性検知用（暗号用途ではない）
    val serviceName: String,   // 保存先サービス名（混在・移行のため記録）
    val createdAt: Instant,    // kotlin.time.Instant
)

data class Attachment(
    val id: AttachmentId,
    val name: String,          // "avatar" 等
    val record: RecordRef,
    val blobId: BlobId,
    val createdAt: Instant,
)
```

`id`（論理ID・署名対象）と `key`（ストレージ位置）を分離する。これで「key は確定して永続化、再導出しない」を守りつつ、将来の移行（serviceName 切替や key 移設）でも `id` を安定参照として保てる。

### 5.2 ポート（core が定義、アダプタが実装）

```kotlin
// 1. ストレージ抽象（dumb: キーは生成も導出もしない）
interface StorageService {
    val name: String                                                  // Blob.serviceName へ
    suspend fun put(key: String, content: ContentSource, meta: ObjectMetadata)
    suspend fun get(key: String): RawSource                           // kotlinx-io。java/Ktor 型に依存しない
    suspend fun exists(key: String): Boolean
    suspend fun delete(key: String)
    suspend fun presignedGetUrl(key: String, ttl: Duration): PresignedUrl?  // 非対応(fs)は null
}

// 2. メタデータ（"最小操作"に絞った狭いポート）
interface MetadataStore {
    suspend fun insertBlob(blob: Blob)
    suspend fun findBlob(id: BlobId): Blob?
    suspend fun deleteBlob(id: BlobId)
    suspend fun insertAttachment(a: Attachment)
    suspend fun findAttachments(record: RecordRef, name: String): List<Attachment>
    suspend fun deleteAttachment(id: AttachmentId)
}

// 3. キー生成ストラテジ（既定: 不透明ランダムトークン）
fun interface KeyGenerator { fun generate(ctx: KeyContext): String }

// 4. 署名（純粋ロジックなので core 内。既定: HMAC-SHA256）
interface ReferenceSigner {
    fun sign(blobId: BlobId, expiresAt: Instant): String              // 不透明トークン
    fun verify(token: String): BlobId?                                // 失効/改竄は null
}
```

core の公開 API は特定言語（Java）依存を避け、Kotlin 多プラットフォーム中立の型で統一する。ストリームは kotlinx-io（`RawSource` / `Source`）、URL は core 内の値クラス `PresignedUrl`、時刻は stdlib の `kotlin.time`（`Instant` / `Clock` / `Duration`、追加依存なし）。core の公開 API に `java.*` 型は出さない。各アダプタは内部で java.nio や各 SDK の型を使ってよいが、ポート境界で上記の中立型へ変換する。

注意: kotlinx-io は現在 0.9.0（pre-1.0）で、これが core の公開 API に露出する。kotlinx-io に破壊的変更が入った場合は本ライブラリも協調メジャー更新が必要（Renovate で追従）。中立性と引き換えのトレードオフとして受容する。

メタデータポートは構想 14 章の「最小」に沿い、MVP は insert/find/delete のみ。`purge_later` と `unattached` はフェーズ2へ先送り。

### 5.3 高レベル API（facade）

```kotlin
class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val clock: Clock = Clock.System,   // kotlin.time.Clock
) {
    suspend fun attach(record: RecordRef, name: String, content: ContentSource): Attachment
    suspend fun attachments(record: RecordRef, name: String): List<Attachment>
    suspend fun detach(attachment: Attachment, purgeBlob: Boolean = true)
    // 配信 API は 7 章
}
```

MVP はストレージ単一構成（1デプロイ1バックエンド。dev=fs, prod=s3 等）。`Blob.serviceName` は移行のため記録するが、`serviceName` による振り分けレジストリはフェーズ2へ先送り。

### 5.4 attach の整合性順序

```
1. content を一時ファイルへスプール → byteSize と checksum を確定（大容量でもメモリ非依存）
2. key/blobId 採番（KeyGenerator）
3. metadata.insertBlob(blob)        ← Blob 行を先に作る
4. service.put(key, spooled, meta)  ← 実体アップロード
5. metadata.insertAttachment(att)
```

この順序により、失敗時の残骸は必ず「孤立 Blob（実体なし or 添付なし）」に限定され、「添付が実体無しを指す」危険な状態は発生しない。孤立 Blob の回収（GC）はフェーズ2。各メタデータ操作は `metadata-exposed-jdbc` 側で個別トランザクションに包む。業務トランザクション相乗りは integration 層（フェーズ2/3）に委ねる（core はトランザクション非依存）。

---

## 6. ストレージアダプタ

### 6.1 storage-fs

- `put`: `root/key` へ書き込み（key の `/` でサブディレクトリ生成）。temp 書き → atomic move で部分ファイルを防ぐ。オブジェクトメタは DB の Blob 行が持つため disk には書かない。
- `get` / `exists` / `delete`: 内部は `java.nio.file.Files` ベース。ポート境界で kotlinx-io `RawSource` へ変換して返す。
- `presignedGetUrl`: `null` を返す（ローカルFSに外部取得可能なURLは存在しない）。
- セキュリティ: 解決後パスが root 配下かを検証（path traversal 防御）。key はランダムトークンのため低リスクだが防御的に行う。
- ブロッキング I/O のため `withContext(Dispatchers.IO)` でディスパッチャ分離。

### 6.2 storage-s3（AWS SDK for Kotlin・suspend ネイティブ）

- 設定: bucket / region / credentials / endpointOverride（S3互換: MinIO/Garage/R2 用）/ forcePathStyle（MinIO 等で必要）。
- `put`=putObject（スプール済み temp から stream・contentType・任意で Content-MD5）、`get`=getObject、`exists`=headObject(404→false)、`delete`=deleteObject。
- SDK の `ByteStream` と core の kotlinx-io 型はアダプタ境界で相互変換する。
- `presignedGetUrl`: SDK の presign 機能に委譲。
- suspend ネイティブのため Dispatchers.IO ラップ不要。core の coroutine に直結。

---

## 7. 配信・署名

### 7.1 構想との矛盾と解消

構想のフェーズ1は「リダイレクトモードでの取得」と「storage-fs」を両方含むが、fs は presigned URL を作れないため 302 リダイレクトできない。redirect-only だと fs は「保存できるが配信できない」になる。

**解消**: 配信を `Delivery` sealed 結果に統一し、ストレージ能力で自動分岐させる。

```kotlin
// 署名参照(HMAC): BlobId+失効を不透明トークン化。アプリの配信URLに埋める
fun AktiveStorage.signedReference(blob: Blob, ttl: Duration): String

// HTTPハンドラ内で呼ぶ。検証 → Blob特定 → 配信方法を決めて返す
suspend fun AktiveStorage.resolveForDelivery(token: String): Delivery?
sealed interface Delivery {
    data class Redirect(val url: PresignedUrl) : Delivery                 // S3: presigned へ 302
    data class Proxy(val blob: Blob, val stream: RawSource) : Delivery    // fs: バイトを stream
}
```

- S3（presigned 対応）→ `Redirect`（既定）。fs（presigned 非対応）→ 自動的に `Proxy` フォールバック。
- MVP の `Proxy` は「`get(key)` でバイトを流すだけ」の最小実装。リッチなプロキシモード（range / cache-control / CDN）はフェーズ2。
- MVP は Web フレームワーク非搭載のため、利用者が `Delivery` を 302 / stream に振り分ける数行を書く。integration-ktor がフェーズ2でそれを梱包する。

### 7.2 署名

- HMAC-SHA256、単一シークレットキー。`sign` は base64url(payload{blobId, expiresAt} + signature)。`verify` は署名・失効を検査し、不正なら null。
- 鍵ローテーションはフェーズ2。
- redirect 時の presigned URL の TTL は「302 を追える短時間」とし、アプリ署名参照の TTL とは独立に扱う。

---

## 8. metadata-exposed-jdbc

### 8.1 テーブル設計

```
aktive_blobs:        id(PK) / key(UNIQUE) / filename / content_type /
                     byte_size / checksum / service_name / created_at
aktive_attachments:  id(PK) / name / record_type / record_id /
                     blob_id(FK→aktive_blobs.id) / created_at
                     INDEX(record_type, record_id, name)   ← findAttachments 用
```

`record_id` は varchar（UUID/ULID/複合キーを排除しないため）。`(record_type, record_id, name)` は has_many を許すため UNIQUE にはせず、ただのインデックスとする（has_one の一意性はアプリ層の関心事）。

### 8.2 実装方針

- Exposed DSL（DAO ではなく）で狭いポートを実装し軽量に保つ。
- suspend 橋渡しは `newSuspendedTransaction(Dispatchers.IO)` でブロッキング JDBC を逃がす。各操作は個別トランザクション。
- スキーマ: `Table` 定義 + 任意の `createSchema()` ヘルパ（テスト/開発用）を提供。本番利用者は自前マイグレーションで管理する（リファレンス DDL を同梱）。マイグレーションツールは強制しない。

---

## 9. キー生成（既定）

`RandomTokenKeyGenerator`: `SecureRandom` → base62/base58（URLセーフ・パディング無し・約 22–28 文字）。`KeyContext`（filename / contentType / recordRef）を渡し、差し替えでプレフィックスや content-hash も可能。既定は context を無視してランダムトークンを返す。

---

## 10. テスト戦略

レイヤード構成:

- **ユニット（高速・Docker 不要）**: core のロジック（attach 順序、署名 sign/verify、キー生成）、storage-fs（一時ディレクトリ）、各アダプタの純粋ロジック。`./gradlew test`。
- **統合（Testcontainers・タグ分離）**: storage-s3 を MinIO コンテナ、`metadata-exposed-jdbc` を Postgres コンテナで検証。`./gradlew integrationTest`。ローカルは `-x integrationTest` で Docker 無しでも回せる。
- **E2E（`integration-tests` モジュール）**: 全モジュールを結線し、添付→Postgres 永続化→MinIO 保存→署名トークン取得→バイト一致 を1本で検証。

---

## 11. 完了判定（Definition of Done）

以下すべてを満たすこと。#1 と #2 は別軸の両輪（#2 が正しさの基盤、#1 が受け入れゲート）。

| # | 完了基準 | 何を担保するか |
| --- | --- | --- |
| 1 | E2E 振る舞いテストが green（受け入れゲート）：`attach → Postgres に Blob+Attachment 永続化 → MinIO に実体保存 → 署名トークンで取得 → バイト列一致` | 中核価値が実際に成立すること（全部品が噛み合う） |
| 2 | 各モジュールのユニット/統合テストが green（基盤） | core ロジック・fs・s3・exposed の個別正しさ（正しさの大半） |
| 3 | `publishToMavenLocal` が全 artifact を POM・署名込みで出力 | 配布可能な形に梱包されていること（機能完成とは別軸） |
| 4 | CI 上で 1〜3 がすべて green | 再現性 |

---

## 12. CI/CD

### 12.1 ワークフロー分割

- **`ci.yml`**（PR + main push）: `lint → (test, integration-test)`
- **`verify-packaging.yml`**（`workflow_run: CI 成功 on main`）: `publishToMavenLocal` による梱包検証。実 Central プッシュではなく「配布可能な形に梱包できるか」の検証であることが名前で分かるようにする。main 限定のため別ファイルに分離し、CI が seed した Gradle キャッシュを再利用する。
- 将来の実 Central リリース（発火方法は検討中）は、さらに別のワークフローとして分離する。

### 12.2 ci.yml

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

# PR は新 push で旧 run を cancel。main は cancel せず全コミット完走
# (各コミット検証を残す=Renovate automerge / setup-gradle cache を毎コミット seed)
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

### 12.3 verify-packaging.yml

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

### 12.4 Actions 固定ポリシー

全 `uses:` を 40 文字コミット SHA + バージョンコメント（`@<sha> # vX.Y.Z`）で固定する。タグ参照は使わない（サプライチェーン対策）。2026-06-15 実測:

| Action | タグ | SHA |
| --- | --- | --- |
| `actions/checkout` | v6.0.3 | `df4cb1c069e1874edd31b4311f1884172cec0e10` |
| `actions/setup-java` | v5.2.0 | `be666c2fcd27ec809703dec50e508c2fdc7f6654` |
| `gradle/actions/setup-gradle` | v6.2.0 | `3f131e8634966bd73d06cc69884922b02e6faf92` |
| `actions/upload-artifact` | v7.0.1 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |

### 12.5 Renovate

`renovate.json`（リポジトリ直下）。mindstock 規約に合わせる:

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

SHA 固定の Action もバージョンコメントを見て追従する。Gradle version catalog も対象。CI の「main は完走」設計が automerge と噛み合う。

---

## 13. ビルドアプローチ

ウォーキングスケルトン優先。

0. **toolchain spike**: Gradle 9.5.1 + Kotlin 2.4.0 + build-logic で空ビルドが通るか検証（ダメなら Kotlin 2.3.20 へフォールバック）。
1. **最短縦貫通**: core の `attach` + storage-fs + 薄いインメモリ metadata で、ポート境界が噛み合うことを最初に検証。
2. storage-s3・`metadata-exposed-jdbc` を肉付け。
3. 配信（`Delivery`）・署名を結線。
4. `integration-tests` で E2E（完了判定 #1）を green に。
5. CI/CD・公開設定・Renovate・Spotless を整える。

---

## 14. リスク

- **Kotlin 2.4.0 の枯れ具合**: ステップ0で検証、ダメなら 2.3.20。
- **kotlinx-io が pre-1.0（0.9.0）**: core 公開 API に露出するため、破壊的変更時は協調メジャー更新が必要。Renovate で追従。
- **Testcontainers 2.x / JUnit 6.x のメジャー差分**: 座標・API を skeleton で吸収。
- **AWS SDK for Kotlin の getObject ストリーム寿命**: lambda スコープでストリームが閉じるため、kotlinx-io への橋渡しに注意。
- **Exposed の suspend トランザクション**: `newSuspendedTransaction` の挙動（接続プール枯渇・ディスパッチャ）を統合テストで確認。

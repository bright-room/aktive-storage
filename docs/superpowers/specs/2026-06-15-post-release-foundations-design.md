# aktive-storage 公開直後の足回り整備 設計

- 日付: 2026-06-15
- 対象: v0.0.1 を Maven Central に公開済みの aktive-storage に対する「公開直後の足回り」整備（Track A）
- 前提: 初版リリース基盤は `docs/superpowers/specs/2026-06-15-maven-central-first-release-design.md` で構築済み

## 目的

公開はされたが利用者向けの土台が欠けている状態を埋める。具体的には、(1) 使い方が分かる README、(2) 公開 API の意図しない破壊を防ぐ ABI 互換チェック、(3) バージョン方針の明文化。SNAPSHOT 公開チャネルは今回は作らず、将来導入の手順だけ残す。

## 確定した方針

- README: **最小 Quickstart**（導入 + 最小コード例1つ + モジュール一覧 + バッジ）
- ABI ツール: **standalone BCV `org.jetbrains.kotlinx.binary-compatibility-validator` 0.18.1**（config-cache 対応済み＝issue #95/#96 closed。Kotlin 2.4 標準の experimental `abiValidation` は将来 KMP 化時の寄せ先として留保）
- バージョン方針: **0.x は API 安定を保証しない。0.MINOR.0（minor）で破壊的変更を含みうる。0.0.PATCH は後方互換のバグ修正。1.0 で安定。**
- リリースノート: 既存 `.github/release.yml` + `--generate-notes` の**自動生成のみ**（新規ファイル不要）
- SNAPSHOT: 今回は作らない。spec 付録に導入手順だけ残す。

## 設計

### 1. README（最小 Quickstart）

`README.md` を差し替える（現状はタイトル + tagline の2行のみ）。構成:

- タイトル + tagline（既存を活かす）+ **バッジ**: Maven Central version（`net.bright-room.aktive-storage:core`）/ License Apache-2.0 / CI ステータス
- **What it is**: 1〜2文（ActiveStorage 相当の、フレームワーク非依存な JVM/Kotlin ファイル添付ツールキット）
- **Install**: BOM + 各モジュールの Kotlin DSL スニペット。BOM 経由でバージョン一元管理する形を提示。
- **Quickstart**: 最小エンドツーエンド例を1つ。storage-fs（`FilesystemStorageService`）+ メタデータストアで「レコードにバイト列を attach → 署名付き delivery URL を取得」までを示す。**`integration-tests/src/test/kotlin/net/brightroom/aktivestorage/it/EndToEndIT.kt` の実フローから蒸留し、実在する public API に基づくこと**（README のコードがコンパイル可能性を保つため）。
- **Modules**: core / storage-fs / storage-s3 / metadata-exposed-jdbc / bom を1行説明の表で。
- **Requirements**: JDK 21。
- **Versioning** 節（下記 §3）と **License**（Apache-2.0）。

YAGNI: 各アダプタの網羅的設定や全ユースケースは書かない（フェーズ2以降の網羅版で）。

### 2. ABI 互換チェック（BCV）

- ルート `build.gradle.kts`（現状空）に BCV プラグインを適用。root 適用で全サブプロジェクトをカバーする。

  ```kotlin
  plugins {
      id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
  }

  apiValidation {
      ignoredProjects += listOf("integration-tests", "bom")
  }
  ```

  - `integration-tests`: 非公開（publish 対象外）。
  - `bom`: `java-platform` でクラスを持たず API が無いため除外。
- 初期 API ダンプを生成: `./gradlew apiDump`。main ソースを持つ公開4モジュール（core / storage-fs / storage-s3 / metadata-exposed-jdbc）に `api/<module>.api` が生成される。これらを**コミットして基準**とする。
- **CI 連携**: `.github/workflows/ci.yml` の `lint` ジョブに `./gradlew apiCheck` ステップを追加。公開 API シグネチャに差分が出たら CI が落ちる。
- 検証: `./gradlew apiCheck` が config-cache 有効のまま BUILD SUCCESSFUL（`Configuration cache entry stored.`）。

> 補足: ルートへのプラグイン適用は、リリース基盤で nmcp を `settings.gradle.kts` に置いて「ルートは空」を保った方針とは別。BCV はプロジェクトプラグイン（settings プラグインが無い）であり、ビルド全体に対する単一の関心事なのでルート適用が正式な置き場所。

### 3. バージョン方針 + リリースノート

- README の **Versioning** 節に明記:
  - 0.x は API 安定を保証しない。`0.MINOR.0`（minor 上げ）で破壊的変更を含みうる。`0.0.PATCH` は後方互換のバグ修正。`1.0` で安定。
  - ABI チェックは「**意図しない**破壊」の検出に使う。意図的な破壊時は `./gradlew apiDump` で `.api` を更新し、minor を上げる、という運用を1行添える。
- リリースノートは既存 `.github/release.yml`（カテゴリ定義済み）+ `on-tag-push` の `gh release create --generate-notes` による**自動生成のみ**。新規ファイル・ワークフロー変更は不要。
- リリース手順そのものは既存 spec（`2026-06-15-maven-central-first-release-design.md`）を参照する旨を README から1行リンク。

### 4. SNAPSHOT（今回は実装しない・付録）

将来 SNAPSHOT 公開を足す場合の手順（このスコープでは**コード・ワークフローを追加しない**）:

- nmcp は `publishAggregationToCentralPortalSnapshots` タスクを既に提供済み。
- `gradle.properties` の `version` を `-SNAPSHOT` 付き（例 `0.2.0-SNAPSHOT`）にしておき、SNAPSHOT 公開ジョブは `-Pversion` 上書きなしで実行する。
- 発火は main push（CI 通過後）か `workflow_dispatch` を選ぶ。利用者が pre-release を必要とするまで延期。
- 利用側は Central Snapshots リポジトリ（`https://central.sonatype.com/repository/maven-snapshots/`）を `repositories {}` に追加する必要がある。

## 検証（成功条件）

- `./gradlew apiCheck spotlessCheck test` が **config-cache 有効のまま** BUILD SUCCESSFUL。
- `api/*.api` ファイルが core/storage-fs/storage-s3 に生成・コミットされている。
- README の Quickstart コードが実在 public API に一致（`EndToEndIT.kt` 由来）。
- CI（`ci.yml` lint）に `apiCheck` が組み込まれている。

## スコープ外

- SNAPSHOT 公開の実装（付録の手順のみ）
- streaming/マルチパート、追加 `storage-*`/`metadata-*`、framework 統合、KMP 本適用
- README の網羅版（アダプタ別詳細設定など）

# aktive-storage 初版 Maven Central リリース設計

- 日付: 2026-06-15
- 対象: aktive-storage MVP（core / storage-fs / storage-s3 / metadata-exposed-jdbc / bom）の初回正式リリース
- 参考: [bright-room/endpoint-gate](https://github.com/bright-room/endpoint-gate) のリリースフロー

## 目的

MVP として実装完了済みの aktive-storage を、初版として Maven Central（Central Portal）へ公開する。リリース発火はタグ Push。将来の Kotlin Multiplatform 化を見据え、アップロード機構には標準 `maven-publish` の上に薄く乗る nmcp を採用する。

## 前提（調査で確認済み・provisioning 不要）

org レベル（bright-room）に以下が揃い、いずれも aktive-storage が selected visibility に含まれることを確認済み:

| 項目 | 用途 |
| --- | --- |
| `CHLOE_CHAN_APP_ID`(var) = `3155244` / `CHLOE_CHAN_APP_PRIVATE_KEY`(secret) | タグ作成用 GitHub App。`protect-tags` ruleset の bypass Integration actor と App ID 完全一致 |
| `PGP_SIGNING_KEY` / `PGP_SIGNING_KEY_PASSPHRASE`(secret) | 成果物の PGP 署名 |
| `SONATYPE_CENTRAL_USERNAME` / `SONATYPE_CENTRAL_PASSWORD`(secret) | Central Portal 認証 |

tag ruleset（aktive-storage）:

- `protect-tags`: 全タグの creation/update/deletion を禁止。bypass は CHLOE_CHAN App（Integration）と 1 Team のみ。
- `require-signed-commits-tag`: 全タグに署名必須。**bypass 無し**。

→ ローカルから `git push --tags` は不可。タグは `workflow_dispatch` から App トークンで GitHub API（`git/tags`→`git/refs`）作成する。API 作成タグは GitHub の web-flow 鍵で verified になり署名要件を満たす。

Central namespace `net.bright-room` は endpoint-gate が同一 Sonatype 認証で公開済みのため検証済みとみなす（初版では再検証しない）。

## 確定した方針

- Central 公開モード: **AUTOMATIC**（検証通過後に自動で一般公開）
- SNAPSHOT 公開経路: **作らない**（タグリリースのみ）
- milestone close ジョブ: **入れない**

## 設計

### 全体フロー

```
[人] workflow_dispatch(version=v0.1.0)
      └─ validate（形式/重複）→ CHLOE_CHAN App token → GitHub API でタグ作成
[tag push: v*]
      ├─ job release: GitHub Release 作成（--generate-notes, 既存 .github/release.yml 利用）
      └─ job publish: nmcp で Central Portal へ集約アップロード（AUTOMATIC）
```

### 1. Gradle: nmcp aggregation

- 各 publish モジュールは現状の `aktive.published`（`maven-publish` + `signing` + Dokka javadoc jar + sources jar）のまま変更しない。nmcp は標準 publication を拾うだけなので、KMP 化時もこの層は不変。
- 集約はルート関心事のため、ルート `build.gradle.kts` に集約プラグインを適用する（「ルートは意図的に空」という現状コメントから外れるが、集約はビルド全体の単一関心事であり妥当）:

  ```kotlin
  plugins { id("com.gradleup.nmcp.aggregation") version "<検証して固定>" }

  dependencies {
      nmcpAggregation(project(":core"))
      nmcpAggregation(project(":storage-fs"))
      nmcpAggregation(project(":storage-s3"))
      nmcpAggregation(project(":metadata-exposed-jdbc"))
      nmcpAggregation(project(":bom"))
  }

  nmcpAggregation {
      centralPortal {
          username = providers.environmentVariable("SONATYPE_CENTRAL_USERNAME")
          password = providers.environmentVariable("SONATYPE_CENTRAL_PASSWORD")
          publishingType = "AUTOMATIC"
      }
  }
  ```

- 全モジュール + BOM を 1 デプロイに束ねて 1 回アップロードし、部分公開事故を防ぐ。`integration-tests` は列挙しないため自動的に対象外。`java-platform`(bom) は 1 component として集約される。
- nmcp の版数は **Gradle Plugin Portal で最新を確認してから固定**する（憶測しない）。

### 2. 署名 env 名の整合

現 `aktive.published` は `SIGNING_KEY` / `SIGNING_PASSWORD` を読むが、org secret 名は `PGP_SIGNING_KEY` / `PGP_SIGNING_KEY_PASSPHRASE`。convention は変更せず、ワークフロー側で以下のようにマッピングする（最小変更）:

```yaml
env:
  SIGNING_KEY: ${{ secrets.PGP_SIGNING_KEY }}
  SIGNING_PASSWORD: ${{ secrets.PGP_SIGNING_KEY_PASSPHRASE }}
```

### 3. バージョン注入

`gradle.properties` の `version=0.1.0-SNAPSHOT` はローカル開発用にそのまま残す。リリースはタグ名から `-Pversion=${TAG#v}` で上書きする（convention は `providers.gradleProperty("version")` を直読みするため `-P` 上書きが効く）。Central は SNAPSHOT を拒否するため、正式版になるのはタグ実行時のみ。

### 4. ワークフロー2本（新規）

**`.github/workflows/on-workflow-dispatch.yaml`**

- `workflow_dispatch` 入力 `version`（例 `v0.1.0`）
- job validate: 形式検証（`^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$`）→ 既存タグ重複チェック
- job push-tag: `actions/create-github-app-token`(CHLOE_CHAN) → `git/tags`+`git/refs` API でタグ作成

**`.github/workflows/on-tag-push.yaml`**（`on: push: tags: ["v*"]`）

- job release: `permissions: contents: write` → `gh release create "$TAG" --title "$TAG" --generate-notes`
- job publish: JDK 21（temurin、CI と統一）→ `./gradlew nmcpPublishAggregationToCentralPortal -Pversion=${TAG#v} --no-configuration-cache`
  - `--no-configuration-cache`: 署名/アップロードの configuration-cache 非互換を回避（`gradle.properties` で config-cache 有効のため）
  - env: `SONATYPE_CENTRAL_USERNAME` / `SONATYPE_CENTRAL_PASSWORD` / `SIGNING_KEY`(=PGP_SIGNING_KEY) / `SIGNING_PASSWORD`(=PGP_SIGNING_KEY_PASSPHRASE)

両ワークフローとも Actions は SHA ピン（既存 CI と同方針）。

### 5. メタデータ補完

- `LICENSE`（Apache-2.0 全文）をリポジトリルートに追加。POM は Apache-2.0 を宣言済みだが実体ファイルが無いため。
- `aktive.published` の POM `scm` に `connection` / `developerConnection` を追加（Central 推奨メタデータ）。

### 6. 検証手段（成功条件）

- ローカル: `./gradlew publishToMavenLocal -Pversion=0.1.0` で全モジュール + BOM の成果物（jar / sources / javadoc / pom、bom は pom）が生成される。
- ステージ確認: `./gradlew nmcpZipAggregation -Pversion=0.1.0` で集約 zip が生成され、中身に全モジュール座標・署名(.asc)が含まれる（アップロードはしない）。
- 既存 `verify-packaging.yml` / `ci.yml` は据え置き。

## スコープ外（初版では実施しない）

- KMP プラグイン適用 / Apple ターゲット / macOS runner / host 別 publication 集約
- Central namespace `net.bright-room` の再検証
- SNAPSHOT 公開経路
- milestone close 自動化

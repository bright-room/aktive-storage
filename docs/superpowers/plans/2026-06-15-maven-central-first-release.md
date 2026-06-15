# aktive-storage 初版 Maven Central リリース Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** aktive-storage MVP を初版として Maven Central（Central Portal）へタグ Push 起点で公開できる状態にする。

**Architecture:** 既存の `aktive.published` convention（maven-publish + signing + Dokka javadoc/sources jar）はそのまま。`settings.gradle.kts` に nmcp settings プラグインを足して全 publish モジュール + BOM を Central Portal へ 1 デプロイで集約アップロードする。タグは ruleset 制約（App のみ作成可・署名必須）のため `workflow_dispatch` から CHLOE_CHAN App トークンで GitHub API 作成し、その push を起点に publish ワークフローが走る。

**Tech Stack:** Gradle (Kotlin DSL, convention plugins), nmcp 1.5.0 (`com.gradleup.nmcp.settings`), GitHub Actions, Sonatype Central Portal, PGP signing.

**設計根拠:** `docs/superpowers/specs/2026-06-15-maven-central-first-release-design.md`

**前提（調査済み・このプランでは作成しない）:**
- org secret/var が aktive-storage から参照可能: `CHLOE_CHAN_APP_ID`(=3155244, var) / `CHLOE_CHAN_APP_PRIVATE_KEY` / `PGP_SIGNING_KEY` / `PGP_SIGNING_KEY_PASSPHRASE` / `SONATYPE_CENTRAL_USERNAME` / `SONATYPE_CENTRAL_PASSWORD`
- `protect-tags`(App のみ) / `require-signed-commits-tag`(署名必須・bypass 無し) が aktive-storage に有効
- ピン済み Action SHA: checkout `df4cb1c069e1874edd31b4311f1884172cec0e10`(v6.0.3) / setup-java `be666c2fcd27ec809703dec50e508c2fdc7f6654`(v5.2.0) / setup-gradle `3f131e8634966bd73d06cc69884922b02e6faf92`(v6.2.0) / create-github-app-token `bcd2ba49218906704ab6c1aa796996da409d3eb1`(v3.2.0)
- 作業ブランチ: `feat/maven-central-release`(既存、spec コミット済み)

---

## File Structure

- `settings.gradle.kts` — 修正: nmcp settings プラグイン適用 + `nmcpSettings` 設定
- `build-logic/src/main/kotlin/aktive.published.gradle.kts` — 修正: POM の scm に connection/developerConnection 追加
- `LICENSE` — 新規: Apache-2.0 全文
- `.github/workflows/on-workflow-dispatch.yaml` — 新規: version 入力でタグ作成
- `.github/workflows/on-tag-push.yaml` — 新規: GitHub Release + Central publish

---

## Task 1: nmcp settings プラグインを適用

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1: settings.gradle.kts を修正**

`plugins {}` ブロックに nmcp settings プラグインを追加し、ファイル末尾に `nmcpSettings` 設定を追加する。修正後の `settings.gradle.kts` 全体:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradleup.nmcp.settings") version "1.5.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aktive-storage"

include("core")
include("storage-fs")
include("storage-s3")
include("metadata-exposed-jdbc")
include("integration-tests")
include("bom")

nmcpSettings {
    centralPortal {
        username = System.getenv("SONATYPE_CENTRAL_USERNAME")
        password = System.getenv("SONATYPE_CENTRAL_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}
```

> **重要（config-cache）:** 認証情報は `System.getenv(...)` で読むこと。`providers.environmentVariable(...)` を使うと nmcp が `beforeProject` ライフサイクルコールバックに `ValueSourceProvider` を載せ、config-cache 直列化に失敗して**全 Gradle ビルドが壊れる**。nmcp 公式 Quickstart も String 代入で示している。

- [ ] **Step 2: プラグイン解決と publish タスク生成を確認**

Run: `./gradlew tasks --all | grep publishAggregationToCentralPortal`
Expected: `publishAggregationToCentralPortal - Publishes the aggregation to the Central Releases repository.` が出力される。あわせて `./gradlew tasks` 自体が config-cache 有効のまま BUILD SUCCESSFUL になること（`Configuration cache entry stored.`）。`integration-tests` 等 publications を持たないモジュールはアップロード対象外（集約に何も寄与しない）。

- [ ] **Step 3: 既存 Spotless が通ることを確認**

Run: `./gradlew spotlessCheck`
Expected: PASS（settings.gradle.kts は spotless 対象外だが、ビルド構成が壊れていないことの確認）。

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts
git commit -m "build: add nmcp settings plugin for Maven Central aggregation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: LICENSE ファイルを追加

POM は Apache-2.0 を宣言済みだが実体ファイルが無い。Central 検証は POM メタデータを見るため必須ではないが、宣言と実体を一致させるため追加する。

**Files:**
- Create: `LICENSE`

- [ ] **Step 1: Apache-2.0 正本を取得**

Run:
```bash
curl -sSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE
```

- [ ] **Step 2: 内容を検証**

Run: `head -1 LICENSE && wc -l LICENSE`
Expected: 1 行目が `                                 Apache License`（先頭に空白）で、行数が 200 行超（正本は 202 行）。

- [ ] **Step 3: Commit**

```bash
git add LICENSE
git commit -m "docs: add Apache-2.0 LICENSE file

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: POM の scm メタデータを補完

Central 推奨の `connection` / `developerConnection` を追加する。

**Files:**
- Modify: `build-logic/src/main/kotlin/aktive.published.gradle.kts`

- [ ] **Step 1: scm ブロックを修正**

`aktive.published.gradle.kts` の現在の行:

```kotlin
                scm { url.set("https://github.com/bright-room/aktive-storage") }
```

を次に置き換える:

```kotlin
                scm {
                    connection.set("scm:git:git://github.com/bright-room/aktive-storage.git")
                    developerConnection.set("scm:git:git@github.com:bright-room/aktive-storage.git")
                    url.set("https://github.com/bright-room/aktive-storage")
                }
```

- [ ] **Step 2: 生成 POM に scm 三要素が入ることを確認**

Run: `./gradlew :core:generatePomFileForMavenPublication -Pversion=0.1.0 && grep -A3 '<scm>' core/build/publications/maven/pom-default.xml`
Expected: `<connection>`, `<developerConnection>`, `<url>` の 3 要素が出力される。

- [ ] **Step 3: Spotless 確認**

Run: `./gradlew spotlessCheck`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add build-logic/src/main/kotlin/aktive.published.gradle.kts
git commit -m "build: add scm connection/developerConnection to published POM

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: ローカルで全モジュール成果物の生成を検証

publish 層が壊れていないことを実際の成果物生成で確認する（署名は CI で実鍵により実行。ローカルでは鍵なしで artifact 生成のみ検証）。

**Files:**
- なし（検証のみ）

- [ ] **Step 1: mavenLocal へ publish（リリース版数で）**

Run: `./gradlew publishToMavenLocal -Pversion=0.1.0`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 4 ライブラリの成果物（jar/sources/javadoc/pom）を確認**

Run:
```bash
ls ~/.m2/repository/net/bright-room/aktive-storage/core/0.1.0/
```
Expected: `core-0.1.0.jar` / `core-0.1.0-sources.jar` / `core-0.1.0-javadoc.jar` / `core-0.1.0.pom` が存在する。

- [ ] **Step 3: BOM（java-platform）が pom のみで publish されることを確認**

Run:
```bash
ls ~/.m2/repository/net/bright-room/aktive-storage/bom/0.1.0/
```
Expected: `bom-0.1.0.pom` が存在する（jar は無くて正しい）。

- [ ] **Step 4: (任意) 署名鍵を持っている場合は署名生成を確認**

Run（鍵を持つ場合のみ）:
```bash
SIGNING_KEY="$(cat /path/to/private.asc)" SIGNING_PASSWORD="***" \
  ./gradlew publishToMavenLocal -Pversion=0.1.0
ls ~/.m2/repository/net/bright-room/aktive-storage/core/0.1.0/*.asc
```
Expected: 各成果物に対応する `.asc` 署名ファイルが生成される。鍵が無い環境では本ステップはスキップしてよい（CI の publish ジョブで検証される）。

- [ ] **Step 5: 検証用に publish したローカル成果物を掃除（任意）**

Run:
```bash
rm -rf ~/.m2/repository/net/bright-room/aktive-storage/*/0.1.0
```
Expected: ローカルの暫定 0.1.0 を除去（コミット対象ではないため git 操作は不要）。

---

## Task 5: タグ Push 起点の publish ワークフローを追加

**Files:**
- Create: `.github/workflows/on-tag-push.yaml`

- [ ] **Step 1: ワークフローを作成**

`.github/workflows/on-tag-push.yaml` を次の内容で作成:

```yaml
name: on-tag-push

on:
  push:
    tags:
      - "v*"

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: false

jobs:
  release:
    name: GitHub Release
    runs-on: ubuntu-latest
    timeout-minutes: 10
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          persist-credentials: false
      - name: Create GitHub Release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ github.ref_name }}
        run: |
          gh release create "$TAG" --title "$TAG" --generate-notes

  publish:
    name: Publish to Maven Central
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          ref: ${{ github.ref_name }}
          persist-credentials: false
      - name: Set up JDK
        uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654 # v5.2.0
        with:
          distribution: temurin
          java-version: "21"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@3f131e8634966bd73d06cc69884922b02e6faf92 # v6.2.0
      - name: Publish to Maven Central
        env:
          SONATYPE_CENTRAL_USERNAME: ${{ secrets.SONATYPE_CENTRAL_USERNAME }}
          SONATYPE_CENTRAL_PASSWORD: ${{ secrets.SONATYPE_CENTRAL_PASSWORD }}
          SIGNING_KEY: ${{ secrets.PGP_SIGNING_KEY }}
          SIGNING_PASSWORD: ${{ secrets.PGP_SIGNING_KEY_PASSPHRASE }}
          TAG: ${{ github.ref_name }}
        run: |
          ./gradlew publishAggregationToCentralPortal -Pversion="${TAG#v}"
```

- [ ] **Step 2: YAML が正しくパースされることを確認**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/on-tag-push.yaml')); print('OK')"`
Expected: `OK`。

- [ ] **Step 3: 設計どおりの要点を目視確認**

確認項目（すべて満たすこと）:
- トリガが `push: tags: ["v*"]`
- top-level `permissions: contents: read`、`release` ジョブのみ `contents: write`
- publish の env が `SIGNING_KEY=PGP_SIGNING_KEY` / `SIGNING_PASSWORD=PGP_SIGNING_KEY_PASSPHRASE` にマッピング
- 実行コマンドが `publishAggregationToCentralPortal -Pversion="${TAG#v}"`（`v` を除去）

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/on-tag-push.yaml
git commit -m "ci: publish to Maven Central on tag push

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: タグ作成用 workflow_dispatch を追加

ruleset により App のみがタグを作成でき、かつタグは署名必須。`workflow_dispatch` から CHLOE_CHAN App トークンを発行し GitHub API でタグを作る（API 作成タグは web-flow 鍵で verified になり署名要件を満たす）。このタグ push が Task 5 を起動する。

**Files:**
- Create: `.github/workflows/on-workflow-dispatch.yaml`

- [ ] **Step 1: ワークフローを作成**

`.github/workflows/on-workflow-dispatch.yaml` を次の内容で作成:

```yaml
name: on-workflow-dispatch

on:
  workflow_dispatch:
    inputs:
      version:
        description: "Release version (e.g. v0.1.0)"
        required: true
        type: string

permissions: {}

jobs:
  validate:
    name: Validate
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Validate version format
        env:
          VERSION: ${{ inputs.version }}
        run: |
          if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
            echo "::error::Invalid version format: $VERSION (expected v1.2.3 or v1.2.3-rc.1)"
            exit 1
          fi
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10 # v6.0.3
        with:
          fetch-depth: 0
          persist-credentials: false
      - name: Check tag does not already exist
        env:
          VERSION: ${{ inputs.version }}
        run: |
          if git rev-parse "refs/tags/${VERSION}" >/dev/null 2>&1; then
            echo "::error::Tag ${VERSION} already exists"
            exit 1
          fi

  push-tag:
    name: Push Tag
    needs: validate
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Generate GitHub App token
        id: app-token
        uses: actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1 # v3.2.0
        with:
          app-id: ${{ vars.CHLOE_CHAN_APP_ID }}
          private-key: ${{ secrets.CHLOE_CHAN_APP_PRIVATE_KEY }}
      - name: Create signed tag via API
        env:
          GH_TOKEN: ${{ steps.app-token.outputs.token }}
          REPO: ${{ github.repository }}
          VERSION: ${{ inputs.version }}
          OBJECT_SHA: ${{ github.sha }}
        run: |
          TAG_SHA=$(gh api "repos/${REPO}/git/tags" \
            --method POST \
            --field "tag=${VERSION}" \
            --field "message=Release ${VERSION}" \
            --field "object=${OBJECT_SHA}" \
            --field "type=commit" \
            --jq '.sha')

          gh api "repos/${REPO}/git/refs" \
            --method POST \
            --field "ref=refs/tags/${VERSION}" \
            --field "sha=${TAG_SHA}"
```

- [ ] **Step 2: YAML が正しくパースされることを確認**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/on-workflow-dispatch.yaml')); print('OK')"`
Expected: `OK`。

- [ ] **Step 3: 設計どおりの要点を目視確認**

確認項目（すべて満たすこと）:
- `workflow_dispatch` 入力 `version`
- `app-id: vars.CHLOE_CHAN_APP_ID` / `private-key: secrets.CHLOE_CHAN_APP_PRIVATE_KEY`
- タグ作成が `git/tags`(annotated) → `git/refs` の 2 段（API 作成 = verified）
- top-level `permissions: {}`（App トークンで操作するため GITHUB_TOKEN 権限は不要）

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/on-workflow-dispatch.yaml
git commit -m "ci: add workflow_dispatch to create release tag via GitHub App

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 仕上げ — spec/plan の最終コミットと PR 準備

**Files:**
- なし（git 操作のみ）

- [ ] **Step 1: 変更全体を確認**

Run: `git log --oneline origin/main..HEAD && git status`
Expected: Task 1–6 のコミット + spec/plan コミットが並び、working tree が clean。

- [ ] **Step 2: 全体ビルド健全性の最終確認**

Run: `./gradlew spotlessCheck test`
Expected: BUILD SUCCESSFUL（既存テストが緑のまま、構成変更で壊れていない）。

- [ ] **Step 3: PR 作成（push はユーザー承認後）**

> 注意: `require-signed-commits-branch` により push には verified 署名が要る。push/PR はユーザーに委ねる（メモリ: 署名ブロックの制約）。実行可能な状態になったら次を案内する:

```bash
git push -u origin feat/maven-central-release
gh pr create --fill --base main
```

---

## リリース手順（実装完了・マージ後の運用メモ）

1. main に変更がマージされた状態で、Actions → `on-workflow-dispatch` を実行し `version` に `v0.1.0` を入力。
2. CHLOE_CHAN App がタグ `v0.1.0` を作成 → `on-tag-push` が起動。
3. `release` ジョブが GitHub Release を作成、`publish` ジョブが `publishAggregationToCentralPortal` で Central Portal へ集約アップロード（`publishingType=AUTOMATIC` のため検証通過後に自動公開）。
4. 数十分後 `https://repo1.maven.org/maven2/net/bright-room/aktive-storage/` に反映を確認。

---

## Self-Review メモ

- **Spec coverage:** spec §1(nmcp settings)→T1 / §2(署名 env)→T5 env / §3(version 注入)→T5 `-Pversion` / §4(ワークフロー2本)→T5,T6 / §5(LICENSE,scm)→T2,T3 / §6(検証)→T4。全項目に対応タスクあり。
- **Placeholder:** なし（nmcp 版数=1.5.0、全 SHA 確定、LICENSE は正本 URL から取得）。
- **型/名称整合:** プラグイン id `com.gradleup.nmcp.settings`、DSL `nmcpSettings`、タスク `publishAggregationToCentralPortal`、env `SIGNING_KEY`/`SIGNING_PASSWORD` を全タスクで一貫使用。

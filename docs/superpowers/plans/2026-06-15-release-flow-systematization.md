# aktive-storage リリースフロー体系化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 稼働中のリリースフローを「タグ駆動・品質ゲート付き・リリース専用チャネル」の明文化モデルへ体系化する。

**Architecture:** パッケージング検証を `ci.yml` の `package` ジョブに統合して `verify-packaging.yml` を廃止し、「CI 緑」をテスト+結合+パッケージングまで含む単一指標にする。`on-workflow-dispatch` のタグ作成前にその対象コミットの CI 緑を必須化する。バージョンはタグを唯一の真実源とし、`gradle.properties` は中立デフォルトに降格。`docs/RELEASING.md` でモデルを明文化する。

**Tech Stack:** GitHub Actions, gh CLI (REST API), Gradle, Markdown.

**設計根拠:** `docs/superpowers/specs/2026-06-15-release-flow-systematization-design.md`

**前提:**
- 作業ブランチ `feat/release-flow-systematization`（最新 main から分岐、spec コミット済み `fbec92b`）
- 公開は AUTOMATIC・ワークフロー名・nmcp/署名/App 経路は据え置き
- 既存ピン済み Action SHA: checkout `df4cb1c069e1874edd31b4311f1884172cec0e10`(v6.0.3) / setup-java `be666c2fcd27ec809703dec50e508c2fdc7f6654`(v5.2.0) / setup-gradle `3f131e8634966bd73d06cc69884922b02e6faf92`(v6.2.0)
- リポジトリは PUBLIC（checkout は contents スコープ不要）

---

## File Structure

- `.github/workflows/ci.yml` — 修正: `package` ジョブ追加
- `.github/workflows/verify-packaging.yml` — 削除
- `.github/workflows/on-workflow-dispatch.yaml` — 修正: validate ジョブに CI 緑ゲート + `actions: read`
- `gradle.properties` — 修正: `version` を `0.0.0` に
- `docs/RELEASING.md` — 新規
- `README.md` / `README.ja.md` — 修正: Versioning 節から RELEASING.md へリンク

---

## Task 1: パッケージング検証を CI に統合し verify-packaging を廃止

**Files:**
- Modify: `.github/workflows/ci.yml`
- Delete: `.github/workflows/verify-packaging.yml`

- [ ] **Step 1: ci.yml に `package` ジョブを追加**

`.github/workflows/ci.yml` の末尾（`integration-test` ジョブの後）に次のジョブを追加する:
```yaml

  package:
    needs: lint
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
      - name: Verify packaging (publish to mavenLocal)
        run: ./gradlew publishToMavenLocal
```

- [ ] **Step 2: verify-packaging.yml を削除**

Run: `git rm .github/workflows/verify-packaging.yml`
Expected: ファイルが削除ステージされる。

- [ ] **Step 3: YAML パースとローカルのパッケージング検証**

Run:
```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('OK')"
./gradlew publishToMavenLocal
```
Expected: `OK` と、`publishToMavenLocal` が BUILD SUCCESSFUL（`package` ジョブが実際に走る内容と同じコマンド）。

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/verify-packaging.yml
git commit -m "ci: fold packaging verification into CI as a package job

Replaces the ad-hoc verify-packaging workflow so that a green CI run now
also implies packaging (publishToMavenLocal) succeeds.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: タグ作成前に対象コミットの CI 緑を必須化

**Files:**
- Modify: `.github/workflows/on-workflow-dispatch.yaml`

- [ ] **Step 1: validate ジョブに権限とゲートステップを追加**

`.github/workflows/on-workflow-dispatch.yaml` の `validate` ジョブ。現在の:
```yaml
  validate:
    name: Validate
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
```
を次に置き換える（`permissions: actions: read` を追加）:
```yaml
  validate:
    name: Validate
    runs-on: ubuntu-latest
    timeout-minutes: 10
    permissions:
      actions: read
    steps:
```

あわせて **`ci.yml` の `paths-ignore`（`docs/**` / `**.md`）を撤廃**する（trigger を `on: { pull_request:, push: { branches: [main] } }` に）。docs/md のみのコミットに CI 実行が作られないとゲートが誤ブロックするため、全コミットに CI 実行を保証してゲートの不変条件を総当たりにする。

続けて、validate ジョブ末尾の現在の最後のステップ:
```yaml
      - name: Check tag does not already exist
        env:
          VERSION: ${{ inputs.version }}
        run: |
          if git rev-parse "refs/tags/${VERSION}" >/dev/null 2>&1; then
            echo "::error::Tag ${VERSION} already exists"
            exit 1
          fi
```
の直後に、次のステップを追加する:
```yaml
      - name: Require green CI on target commit
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          REPO: ${{ github.repository }}
          SHA: ${{ github.sha }}
        run: |
          CONCLUSION=$(gh api "repos/${REPO}/actions/workflows/ci.yml/runs?head_sha=${SHA}&per_page=1" \
            --jq '.workflow_runs[0].conclusion // "none"')
          if [ "$CONCLUSION" != "success" ]; then
            echo "::error::CI is not green for ${SHA} (conclusion: ${CONCLUSION}). Refusing to tag."
            exit 1
          fi
          echo "CI is green for ${SHA}."
```

- [ ] **Step 2: YAML パースを確認**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/on-workflow-dispatch.yaml')); print('OK')"`
Expected: `OK`。

- [ ] **Step 3: 設計どおりの要点を目視確認**

確認項目:
- `validate` ジョブに `permissions: actions: read` がある（top-level の `permissions: {}` は据え置き）。
- ゲートが `gh api .../actions/workflows/ci.yml/runs?head_sha=${SHA}` の `conclusion` を見て `success` 以外で `exit 1`。
- `GH_TOKEN`/`REPO`/`SHA` は `env:` 経由（インジェクション安全）。
- `push-tag` ジョブは未変更。

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/on-workflow-dispatch.yaml
git commit -m "ci: require green CI on the target commit before tagging a release

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: gradle.properties の version を中立デフォルトに降格

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: version を 0.0.0 に変更**

`gradle.properties` の現在の行:
```
version=0.1.0-SNAPSHOT
```
を次に置き換える:
```
version=0.0.0
```
（`group=net.bright-room.aktive-storage` の行は変更しない。）

- [ ] **Step 2: 反映を確認**

Run: `./gradlew properties -q | grep "^version:"`
Expected: `version: 0.0.0`。

- [ ] **Step 3: ビルド構成が壊れていないことを確認**

Run: `./gradlew spotlessCheck`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "build: demote gradle.properties version to neutral default (tag is source of truth)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: RELEASING.md を作成し README からリンク

**Files:**
- Create: `docs/RELEASING.md`
- Modify: `README.md`, `README.ja.md`

- [ ] **Step 1: docs/RELEASING.md を作成**

`docs/RELEASING.md` を次の内容で作成:

````markdown
# Releasing

aktive-storage uses a **tag-driven, gated** release flow. Releases are the only published channel (no SNAPSHOTs), and the git tag is the single source of truth for the released version.

## Channels

- **Release** — published to Maven Central on tag push (`v*`). This is the only channel.
- There is **no SNAPSHOT** channel.

## How to cut a release

1. Ensure the commit you want to release is on `main` and its CI run has succeeded.
2. Run the **`on-workflow-dispatch`** workflow (Actions → on-workflow-dispatch → Run workflow) with `version` set to `vX.Y.Z` (e.g. `v0.1.0`).
3. That workflow:
   - validates the version format and that the tag does not already exist,
   - **refuses to create the tag unless the target commit's CI is green** (lint / test / integration-test / package),
   - creates a signed tag via the GitHub API using the `CHLOE_CHAN` GitHub App.
4. The tag push triggers **`on-tag-push`**, which:
   - creates a GitHub Release with auto-generated notes,
   - publishes all modules + the BOM to Maven Central via nmcp (`publishingType=AUTOMATIC` — released automatically once the Central Portal validates the deployment).

## Versioning

aktive-storage follows semantic versioning with pre-1.0 semantics:

- `0.x` does not guarantee API stability. A minor bump (`0.MINOR.0`) may include breaking changes; a patch bump (`0.0.PATCH`) is backward-compatible fixes only. The public API stabilizes at `1.0`.
- The public ABI is guarded in CI by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) (`apiCheck`), which fails on **unintended** breaks.
- For an **intentional** API change: run `./gradlew apiDump` to update the `api/*.api` baselines, commit them, and release a **minor** bump.

The released version comes entirely from the tag (`-Pversion=<tag without the leading v>`). `gradle.properties` holds a neutral build default (`0.0.0`) used only for local builds.

## Why tags are created by a GitHub App

The repository's tag ruleset allows only the `CHLOE_CHAN` GitHub App to create tags and requires every tag to be signed. Tags created through the GitHub API are signed by GitHub's web-flow key (verified), which satisfies the rule. This is why releases are cut via `on-workflow-dispatch` (which mints an App token) rather than by pushing a tag locally.
````

- [ ] **Step 2: README.md の Versioning 末尾を RELEASING.md にリンク**

`README.md` の現在の行:
```
Releases are published to Maven Central on tag push.
```
を次に置き換える:
```
Releases are published to Maven Central on tag push — see [Releasing](docs/RELEASING.md).
```

- [ ] **Step 3: README.ja.md の対応行もリンク**

`README.ja.md` の現在の行:
```
リリースはタグ Push で Maven Central に公開される。
```
を次に置き換える:
```
リリースはタグ Push で Maven Central に公開される（手順は [Releasing](docs/RELEASING.md)）。
```

- [ ] **Step 4: リンク先の存在を確認**

Run: `ls docs/RELEASING.md && grep -n "docs/RELEASING.md" README.md README.ja.md`
Expected: `docs/RELEASING.md` が存在し、両 README にリンクが1つずつある。

- [ ] **Step 5: Commit**

```bash
git add docs/RELEASING.md README.md README.ja.md
git commit -m "docs: document the release model in RELEASING.md and link from READMEs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 最終検証と仕上げ

**Files:**
- なし（検証 + git 操作）

- [ ] **Step 1: 全体検証（config-cache 有効のまま）**

Run: `./gradlew apiCheck spotlessCheck test publishToMavenLocal`
Expected: BUILD SUCCESSFUL、末尾に `Configuration cache entry stored.`。

- [ ] **Step 2: 残存 workflow と YAML 健全性を確認**

Run:
```bash
ls .github/workflows/
python3 -c "import yaml; [yaml.safe_load(open(f)) for f in ['.github/workflows/ci.yml','.github/workflows/on-tag-push.yaml','.github/workflows/on-workflow-dispatch.yaml']]; print('all OK')"
```
Expected: `ci.yml` / `on-tag-push.yaml` / `on-workflow-dispatch.yaml` の3本のみ（`verify-packaging.yml` は無い）、`all OK`。

- [ ] **Step 3: ブランチ状態確認**

Run: `git log --oneline origin/main..HEAD && git status`
Expected: Task 1–4 の4コミット + spec/plan コミットが並び、working tree clean。

- [ ] **Step 4: push と PR（push はユーザー承認後）**

> 注意: `require-signed-commits-branch` により push には verified 署名が要る（`commit.gpgsign=true` で署名済み）。push/PR はユーザーに委ねる。

```bash
git push -u origin feat/release-flow-systematization
gh pr create --fill --base main
```

---

## Self-Review メモ

- **Spec coverage:** §1 ゲート→T2 / §2 パッケージング統合+verify-packaging 廃止→T1 / §3 version 降格→T3 / §4 RELEASING.md+README リンク→T4 / 据え置き（AUTOMATIC・名称・nmcp）→意図的に変更なし。検証ゴール→T5。
- **Placeholder:** なし（ジョブ全文・ゲートスクリプト全文・RELEASING.md 全文・SHA 確定）。
- **整合:** ゲートが見る `ci.yml` の success は T1 で追加した `package` を含む（パッケージング破壊もゲートが捕捉）。`actions: read` はワークフロー実行一覧 API に必要な最小権限。`publishToMavenLocal` コマンドは T1 の package ジョブ・T5 検証で一貫。

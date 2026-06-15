# aktive-storage リリースフロー体系化 設計

- 日付: 2026-06-15
- 対象: 既に稼働中のリリースフロー（PR #4 で構築、endpoint-gate 踏襲）を、品質ゲート付きの明文化されたリリースモデルへ体系化する
- 前提: ABI 互換チェック（apiCheck）が CI に導入済み（PR #5）。SNAPSHOT は導入しない

## 目的

現状はトリガ別ワークフロー（endpoint-gate 踏襲）の寄せ集めで、(a) 公開前の品質ゲートが無い、(b) バージョンの真実源が曖昧、(c) パッケージング検証がアドホック、という弱さがある。これを「**タグ駆動・ゲート付き・リリース専用チャネル**」の一本のモデルへ体系化する。

## 確定した方針

- **SNAPSHOT は体系から外す**（リリース＝タグのみ）
- 品質ゲート: **タグ作成前に対象コミットの CI 緑を検査**（on-workflow-dispatch 側）
- Central 公開承認: **AUTOMATIC 維持**（手動ゲートは置かない）
- バージョン真実源: **タグが唯一の真実源**（gradle.properties は中立デフォルトに降格）

## 原則

リリースはタグ駆動。タグが版の唯一の真実源。緑のコミットしかタグを打てない。公開は Central Portal の検証通過後に自動。

```
[人] on-workflow-dispatch(version=vX.Y.Z)
      ├─ validate: version 形式 + タグ重複 + 対象コミットの CI=success（緑ゲート）
      └─ push-tag: CHLOE_CHAN App トークンで GitHub API によりタグ作成
[tag push v*] on-tag-push
      ├─ release: GitHub Release（--generate-notes）
      └─ publish: nmcp で Central 公開（AUTOMATIC）
```

## 設計

### 1. 品質ゲート（`on-workflow-dispatch.yaml` の validate ジョブに追加）

タグを作成する前に、対象コミット `github.sha` に対する `ci.yml` の最新実行が成功しているかを確認する。未実行・実行中・失敗なら中止してタグを作らない。

```bash
CONCLUSION=$(gh api "repos/${REPO}/actions/workflows/ci.yml/runs?head_sha=${SHA}&per_page=1" \
  --jq '.workflow_runs[0].conclusion // "none"')
if [ "$CONCLUSION" != "success" ]; then
  echo "::error::CI is not green for ${SHA} (conclusion: ${CONCLUSION}). Refusing to tag."
  exit 1
fi
```

- `github.sha` は dispatch 実行時の対象 ref（既定ブランチ HEAD、または UI で選んだ ref）の SHA。
- 既存の version 形式チェック（`^v[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$`）とタグ重複チェックはそのまま残す。
- `REPO`/`SHA` は `env:` 経由で渡す（`github.repository` / `github.sha`）。この CI 状態確認は GITHUB_TOKEN の `actions: read` を要するため、validate ジョブに `permissions: { actions: read }` を付与する（dispatch の top-level `permissions: {}` は据え置き、ジョブ単位で最小付与）。

### 2. パッケージング検証を CI に統合（`verify-packaging.yml` を廃止）

現状 `verify-packaging.yml` は CI 成功後に workflow_run で連鎖し main 限定で `publishToMavenLocal` を走らせるアドホックな構成。これを `ci.yml` の新規 `package` ジョブとして取り込む。

- `ci.yml` に `package` ジョブを追加: checkout → JDK 21 → setup-gradle → `./gradlew publishToMavenLocal`（署名なし。PR/fork でも secrets 不要で動く）。
- `lint`/`test`/`integration-test` ジョブは現状維持。`package` は `lint` の後（`needs: lint`）に置き、テスト系と並走させる。
- `verify-packaging.yml` を削除。
- 効果: 「CI 緑」がテスト + 結合 + **パッケージング**まで含む単一の意味を持ち、§1 のゲートがパッケージング破壊も自動カバーする。検出タイミングが main 後追いから全 PR へ前倒しされる。

### 3. バージョンの真実源

- `gradle.properties` の `version=0.1.0-SNAPSHOT` を **`version=0.0.0`** に変更（ビルド用の中立デフォルト）。リリースは `-Pversion=${TAG#v}` で上書きするため、タグが唯一の真実源。
- 「維持されない予告版（0.1.0-SNAPSHOT）」を消して誤解を断つ。semver の大小検証は導入しない（YAGNI。タグ重複チェックで二重リリースは防げる）。
- `group` 行は変更しない。

### 4. リリースモデルの明文化（`docs/RELEASING.md` 新規）

体系化の本体ドキュメント。最低限の記載:

- チャネル: リリース専用（タグ駆動）。SNAPSHOT は提供しない。
- リリース手順: GitHub Actions の `on-workflow-dispatch` を `version=vX.Y.Z` で実行 → 自動でタグ作成 → `on-tag-push` が Release 作成 + Central 公開。
- ゲート: 対象コミットの CI（lint/test/integration-test/package）が緑でなければタグは作られない。
- バージョン方針との接続: `0.x` は破壊許容、意図的な API 破壊は `./gradlew apiDump` で `.api` を更新し **minor を上げる**。意図しない破壊は CI の `apiCheck` が止める。
- タグ作成が GitHub App 経由である理由: tag ruleset（App のみ作成可・署名必須）。API 作成タグは web-flow 署名で verified。
- 公開は AUTOMATIC（Portal 検証通過後に自動公開）。
- README の Versioning 節からこの文書へリンクする。

## 据え置き（変更しない）

- Central 公開は AUTOMATIC のまま。手動承認ゲートは置かない。
- ワークフロー名（`on-tag-push` / `on-workflow-dispatch`）は据え置き（外部参照の破壊回避。役割は RELEASING.md に明記）。
- nmcp 集約・署名・App トークン経路・`.github/release.yml` による自動リリースノートは現状維持。

## 検証（成功条件）

- 非緑（または CI 未実行）コミットに対する `on-workflow-dispatch` がゲートで失敗し、タグが作られない。
- `ci.yml` の `package` ジョブで `publishToMavenLocal` が緑。
- `verify-packaging.yml` 削除後も main の CI 一式が緑。
- `docs/RELEASING.md` がフロー・ゲート・バージョン方針接続を網羅し、README からリンクされる。

## スコープ外

- SNAPSHOT 公開チャネル、追加の配信チャネル
- 自動バージョン bump（conventional commits / release-please 等）
- ワークフローの改名、nmcp/署名/App 経路の変更
- semver の大小・連続性検証

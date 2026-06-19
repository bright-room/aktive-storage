# 設計ドキュメント: コードレビュー指摘の修正（2026-06-18）

## 1. 背景・目的

`main` ブランチ全体のコード品質レビュー（10アングルのマルチエージェント＋Codex 独立クロスチェック）で確認された 14 件の指摘と、ユーザー追加の方針（`catch (Throwable)` を `Exception` へ狭める）を、安全に修正する。原案は `docs/jvm-attachment-storage-design.md`。

レビュー総評: モジュール境界・設計不変条件・ライフサイクル順序は忠実で素性は良い。指摘は (a) variant 処理の取りこぼし（無音の誤出力）、(b) クロスサービス・ガードの抜け、(c) 防御的バリデーション/堅牢性の不足 に集中。データ破壊級はなし。

各修正は **「バグを再現するテストを書き、それを通す」（TDD）** で進める。

## 2. スコープ

修正対象 15 項目をテーマ別 4 PR に分割する。

- PR① variant 正しさ
- PR② クロスサービス＋セキュリティ
- PR③ 堅牢性
- PR④ 品質リファクタ

設計で意図的に変更しない項目は §8 に明示する。

## 3. 確定した設計判断（ブレスト結果）

| 論点 | 決定 | 根拠 |
| --- | --- | --- |
| Rotate 非直角 | 構築時に拒否（直角のみ許可） | 離散 API として割り切り、無音の誤出力を構築時点で排除。実装最小。 |
| 片軸 Resize | 欠けた軸をアスペクト比から算出 | 「指定軸に合わせて等比リサイズ」の直感に一致し、片軸用途を維持。design も片軸を許可。 |
| ネスト variant | variant-of-variant を禁止 | 「variant は常に元画像の派生」にモデルを統一。purge の再帰化や FK cascade の中途半端さを回避。 |
| variant メモリ | 設定可能な最大入力サイズ（既定 50 MiB） | `blob.byteSize` で download 前に安価判定でき、OOM/DoS を能動的に防止。 |
| variant 失敗処理 | port に専用例外 `DuplicateVariantException` を導入 | 重複（無害）と真の失敗を型で区別し、補償削除と Cancellation 巻き込み回避を両立。 |
| 列長上限 | filename を `text` 化＋他列は core で長さ検証 | filename はユーザ由来で伸びうる表示メタ。key 等は短く保つ前提だがカスタム生成器対策に検証。 |
| exists() | port から削除（YAGNI） | core 未使用。0.x のため破壊的変更は許容（ABI baseline 再生成・バージョン minor 以上）。 |

補助的な既定（ブレストで合意済みまたは妥当な既定として確定）:

- HMAC 最小鍵長: 32 バイト（256bit）。
- `resolveForDelivery` の非所有 blob: `null` を返す（detach/reclaim の skip と整合）。
- `maxVariantSourceBytes` 超過時: 専用例外 `VariantSourceTooLargeException`（アプリが 413 等に変換できるよう catch 可能に）。
- 所有判定 `owns(blob)` を private ヘルパーに集約し 4 箇所で使用（variant は throw、detach/reclaim/resolveForDelivery は skip/null）。

## 4. PR 別 修正詳細

### PR① variant 正しさ

1. **Rotate 非直角の無音スキップ（高）**
   - 変更: `core/.../Variant.kt` の `Transform.Rotate.init` に `require(degrees % 90 == 0) { ... }`。
   - テスト: `Rotate(45)` は `IllegalArgumentException`、`Rotate(0/90/180/270/-90)` は構築可。

2. **片軸 Resize の拡大抑制（高）**
   - 変更: `variant-scrimage/.../ScrimageVariantProcessor.applyResize`。片軸が null の場合、元画像のアスペクト比から他軸を算出（`height = round(width * srcH / srcW)` ／その逆）してから FIT/LIMIT/FILL を適用。
   - テスト: 1000×500 に `Resize(2000, null, FIT)` → 2000×1000。`Resize(null, 250, FIT)` → 500×250。

3. **ネスト variant の禁止（中）**
   - 変更: `MetadataStore` に `suspend fun isVariantBlob(blobId: BlobId): Boolean` を追加（Exposed: `VariantRecordsTable` を `variantBlobId` で引く／fake 実装）。`AktiveStorage.variant()` 入口で入力 blob が派生なら `IllegalArgumentException`。
   - テスト: variant の variant 要求が例外。通常 origin は従来通り生成。

4. **variant 失敗処理（中、#5/#7）**
   - 変更:
     - `core` に `class DuplicateVariantException(...) : RuntimeException`（Exception 系）を新設。
     - `MetadataStore.insertVariant` の契約: `(originBlobId, variationDigest)` 一意制約違反時は `DuplicateVariantException` を投げる。`ExposedMetadataStore` が PK 衝突（`ExposedSQLException` の一意制約）をこれに変換。
     - `AktiveStorage.variant()`: `catch (e: DuplicateVariantException) { findVariant(...) ?: throw e }`、それ以外の失敗は `withContext(NonCancellable) { service.delete(key) }` で補償してから再throw。`CancellationException` は `DuplicateVariantException` でないため握らず伝播。
   - テスト: (a) 同時生成の収束（重複は既存を返す）、(b) 非重複失敗で実体（put 済みオブジェクト）が残らない。

5. **variant メモリ/OOM（中、#9）**
   - 変更: `AktiveStorage` コンストラクタに `maxVariantSourceBytes: Long = 50L * 1024 * 1024`。`variant()` は `service.get` の **前** に `blob.byteSize > maxVariantSourceBytes` を判定し `VariantSourceTooLargeException` を throw。併せて variant 経路の防御コピーを 1 つ削減（内部用の非コピー ContentSource を用いる）。
   - テスト: 51 MiB blob で download 前に例外、50 MiB は通る。

ABI: `Ports.kt`（`isVariantBlob` 追加）、`AktiveStorage` コンストラクタ引数追加、新例外 2 つ → **baseline 再生成**。

### PR② クロスサービス＋セキュリティ

1. **resolveForDelivery 所有ガード抜け（中〜高、#3／Codex 独自）**
   - 変更: `AktiveStorage` に `private fun owns(blob: Blob) = blob.serviceName == service.name`。`resolveForDelivery` は非所有なら `null` を返す。`detach`/`reclaimUnattached`/`variant` の既存判定もこのヘルパーに統一（variant は従来通り throw、他は skip/null）。
   - テスト: 共有 MetadataStore で他サービス所有 blob の解決が `null`。

2. **HMAC 弱鍵（中〜高、#4）**
   - 変更: `HmacReferenceSigner` コンストラクタに `require(secretKey.size >= 32) { ... }`。
   - テスト: 32 バイト未満の鍵で `IllegalArgumentException`。
   - 付随: 既存テスト/IT の鍵（10–12 文字）を 32 バイト以上へ更新（同 PR 内）。

3. **FS パス安全（低〜中、#8）**
   - 変更: `FilesystemStorageService.resolveSafe`。制御文字（NUL 含む）を含むキーを拒否し、正規化（realpath 相当）後のパスが `root` 配下であることを確認。
   - テスト: NUL/制御文字入りキー拒否、symlink 親経由の脱出防止。

ABI: 影響なし。

### PR③ 堅牢性

1. **`catch (Throwable)` を `Exception` へ（ユーザー指示）**
   - 変更: `Spool.kt:51` / `S3StorageService.kt:55` / `FilesystemStorageService.kt:35` の `catch (e: Throwable)` を `catch (e: Exception)` に。いずれも cleanup→rethrow のため、`Error` 系（OOM 等）は cleanup されず素通し（＝意図通り、握りつぶさない）。`CancellationException` は `Exception` なので temp 掃除は維持。
   - テスト: 既存の例外時 temp 掃除テストが維持されること（必要に応じ追加）。

2. **S3 get の temp リーク（中、#8）**
   - 変更: `S3StorageService.get`。`DeletingFileSource(tempFile)` の構築（＝ファイル open）を、失敗時に temp を削除する try の内側で行う。
   - テスト: temp open 失敗時にファイルが残らない。

3. **CPU 作業が Dispatchers.IO（中、#10）**
   - 変更: `ScrimageVariantProcessor.process` を `Dispatchers.Default` に。入力は in-memory ContentSource でブロッキング I/O なし。
   - テスト: 変換結果の同等性（dispatcher 変更で挙動不変）。

4. **attach 補償がキャンセルで飛ぶ（中、#7）**
   - 変更: `AktiveStorage.attach` の put 失敗補償 `deleteBlob` を `withContext(NonCancellable)` で実行し `throw e`。
   - テスト: put 中キャンセルでも blob 行が補償削除される。

5. **列長超過で attach 失敗（低〜中、#11）**
   - 変更: `Tables.kt` の `filename` を `text()` に（`createSchema` 追従）。`ExposedMetadataStore` 挿入前に key/checksum/serviceName 長を検証し明確な例外。
   - テスト: 長大 filename で attach 成功、超過 key で明確な例外。

ABI: 影響なし（`Tables` は internal、他は内部実装）。

### PR④ 品質リファクタ

1. **exists() 削除（低、#13）**
   - 変更: `StorageService.exists` を削除。`S3StorageService`/`FilesystemStorageService` 実装・`InMemoryStorageService` fake・テストの存在 assertion を削除（テストは get/blobOf 等で代替検証）。
   - ABI: **メソッド削除＝破壊的変更**。baseline 再生成、バージョンは minor 以上。

2. **Blob 構築の重複（低、#14）**
   - 変更: `AktiveStorage` に `private fun newBlob(key: String, filename: String, spooled: SpooledContent): Blob` を抽出し attach/variant で共有。
   - テスト: 既存の attach/variant テストが green のまま（リファクタ）。

ABI: `StorageService` からメソッド削除 → **baseline 再生成**。

## 5. ABI / スキーマ / バージョン影響

- ABI 影響 PR: ①（追加）、④（削除）。各 PR で `binary-compatibility-validator` の baseline を再生成。
- スキーマ変更: ③ の filename `text` 化（`createSchema` はテスト/開発用。本番マイグレーションは利用者責務）。
- バージョン: ④ の公開メソッド削除により次版は **minor 以上**（0.x のため破壊的変更は SemVer 上許容）。

## 6. 推奨マージ順

`AktiveStorage`/`ExposedMetadataStore`/`Scrimage`/`FilesystemStorageService` を複数 PR が触るためリベース調整が出る。コンフリクトと依存を抑える順:

1. PR③ 堅牢性（機械的・低リスク、Throwable 含む。土台）
2. PR② セキュリティ（`owns()` ヘルパー導入）
3. PR① variant（最大の挙動変更。`owns()` の throw 分岐と整合）
4. PR④ 品質（exists 削除＋最終 ABI baseline 確定）

各 ABI 影響 PR（①④）で baseline を再生成、テスト鍵更新（②）と filename text 化（③）も同 PR 内で完結。

## 7. テスト方針

- 各不具合につき、まず再現テスト（赤）を書いてから修正（緑）。
- 既存ユニット/IT を回帰として維持。variant 系は `core` のユニット（fake）＋ `variant-scrimage` ユニット、E2E（MinIO/PG の `EndToEndIT`）は現行どおり。
- `EndToEndIT` は variant を未カバーのため、PR① で variant の最小 E2E もしくは core ユニットで生成・再利用・カスケード purge・上限超過を担保する。

## 8. 設計で意図的に変更しない項目（design-accepted tradeoffs）

レビューで検討したが、設計が明示的に許容しているため修正しない:

- core のトランザクション非依存に伴う共有 blob detach の TOCTOU（相乗りは integration 層の責務）。
- `insertAttachment` 失敗時の孤立 blob（blob 行ありなので `reclaimUnattached` が回収）。
- 同時 variant 生成の収束（決定的キー＋一意制約で収束、PR① の専用例外で明示化）。
- MD5 をチェックサム/digest に使用（整合性・同一性用途のみで可）。

## 9. 残された論点

- PR① が比較的大きい。実装計画段階で必要ならサブ分割（例: 幾何変換系と失敗処理系）を検討。
- `resolveForDelivery` 非所有時に `null` でなく明示例外を望む場合は要相談（現状は null で skip 整合）。

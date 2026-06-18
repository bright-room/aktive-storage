# aktive-storage 画像 variant（遅延生成・派生 Blob）設計

- 日付: 2026-06-18
- 対象: v0.0.2 公開済み aktive-storage。`core` に画像変換の抽象（ポート）と遅延 variant 取得 API を追加し、`variant-scrimage`（新規アダプタ）に Scrimage ベースの実装を置く。
- 前提: プロジェクトは現状 **JVM 専用**。`java.*` 排除方針は **`core` のオーケストレーション層限定**であり、アダプタ（`storage-fs` / `storage-s3` / `metadata-exposed-jdbc`）は元来 JVM 依存。variant の変換実装も同様にアダプタへ隔離する。

## 目的

Rails の ActiveStorage variant 相当の機能を、本ライブラリのポート + アダプタの立て付けで提供する：

1. 画像 Blob に変換（リサイズ・クロップ・回転・グレースケール・フォーマット変換）を適用した**派生画像**を、**初回アクセス時に遅延生成**してストレージにキャッシュ・再利用する。
2. 派生画像も**通常の `Blob`** として記録し、既存の `signedReference` / `resolveForDelivery` 配信経路にそのまま乗せる（新規配信コードを足さない）。
3. 変換エンジン（Scrimage 等の JVM/外部ライブラリ）への依存は **`core` の外（`variant-scrimage`）に隔離**し、`core` はポート（`VariantProcessor`）だけを知る。

## スコープ

- **対象**: `core` への `Variation` / `Transform` / `VariantProcessor`（ポート）/ `AktiveStorage.variant()` 追加、`MetadataStore` への variant 操作追加、削除ライフサイクルとの整合。Exposed アダプタへの `variant_records` 実装。新規 `variant-scrimage` アダプタ。
- **対象外**: 動画・PDF プレビュー、変換ジョブのスケジューリング（いつ生成するかは利用者責務／遅延生成のみ）、`core` 既存オーケストレーションの `java.*` 方針の変更。

## 確定した方針（ブレインストーミングの決定）

- **変換範囲**: フル相当。初版 `Transform` は **Resize / Crop / Rotate / Grayscale / Convert(format, quality)** の 5 種。
- **変換バックエンド**: 外部ライブラリに委譲（**Scrimage** を軸）。正確なバージョン・座標は実装時に確認・ピン留めし、Renovate 管理に乗せる。変換ロジックは自前実装しない。
- **生成タイミング**: **遅延生成**（Rails 型）。初回 `variant(blob, variation)` 要求時に生成・保存し、以降は記録を引いて再利用。
- **同定・追跡モデル**: **派生も Blob として記録**。`variant_records` 表で `(origin_blob_id, variation_digest) → variant_blob_id` をマップ。
- **ABI 変化を許容**: 公開インターフェース追加（`Variation` / `Transform` / `VariantProcessor` / `MetadataStore` メソッド）とコンストラクタ引数追加で公開 API が変化する。`0.x` の minor bump で許容し全モジュールの `apiDump` を再生成する。

## 設計

### 1. モジュール構成

```text
core/                 ← Variation / Transform / VariantProcessor(ポート) / variant() / MetadataStore 拡張
variant-scrimage/     ← (新規) VariantProcessor の Scrimage 実装。Scrimage 依存はここに隔離
```

- `settings.gradle.kts` に `include("variant-scrimage")`、BOM の constraints に新モジュールを追加。
- `variant-scrimage` は共通ビルドプラグイン（`org.jetbrains.kotlin.jvm` + `jvmToolchain(21)`）と BCV を他アダプタと同様に適用。

### 2. `core` のポートと値型

```kotlin
/** 画像変換のバックエンド抽象。元画像を読み、variation を適用した結果を返す。 */
public fun interface VariantProcessor {
    /** content は呼び出し側が close する。返す ContentSource も同様。変換は画像全体をメモリ展開する（intrinsic）。 */
    public suspend fun process(source: ContentSource, variation: Variation): ContentSource
}

/** 変換操作の順序付き列。決定的な canonicalForm を持つ。digest は AktiveStorage 側で算出する。 */
public class Variation private constructor(public val transforms: List<Transform>) {
    /** 操作列の安定した正規化文字列（variant_records のキー・派生ストレージキー導出のハッシュ元）。 */
    public val canonicalForm: String

    public companion object {
        public fun of(vararg transforms: Transform): Variation
    }
}

public sealed interface Transform {
    public data class Resize(val width: Int?, val height: Int?, val mode: ResizeMode) : Transform
    public data class Crop(val width: Int, val height: Int, val gravity: Gravity) : Transform
    public data class Rotate(val degrees: Int) : Transform
    public data object Grayscale : Transform
    public data class Convert(val format: ImageFormat, val quality: Int?) : Transform
}

public enum class ResizeMode { FIT, LIMIT, FILL }   // fit=内接, limit=拡大しない, fill=外接+crop
public enum class Gravity { CENTER, NORTH, SOUTH, EAST, WEST }
public enum class ImageFormat { JPEG, PNG, WEBP }
```

- 入出力を `ContentSource`（既存型: `filename` / `contentType` / `open(): RawSource`）で統一し、`variant()` の保存経路を既存の `spool` + `Checksum` にそのまま乗せる。
- `Variation.canonicalForm` は `transforms` の**正規化文字列**。digest は `AktiveStorage.variant()` 内で `canonicalForm` を**注入済み `Checksum` でハッシュ → base64url（パディング無し・キー安全）**で算出する（`Variation` 自身は `Checksum` を持たない）。同じ操作列は常に同じ digest になること。`Checksum` を流用することで新規ハッシュ依存を足さない。

### 3. `AktiveStorage` の variant 取得 API

```kotlin
public class AktiveStorage(
    private val service: StorageService,
    private val metadata: MetadataStore,
    private val signer: ReferenceSigner,
    private val keyGenerator: KeyGenerator = RandomTokenKeyGenerator(),
    private val checksum: Checksum = Md5Checksum(),
    private val variantProcessor: VariantProcessor? = null,   // ★追加（既定 null = variant 無効）
    private val clock: Clock = Clock.System,
) {
    /**
     * blob に variation を適用した派生 Blob を返す（遅延生成）。
     * 既存の variant 記録があればそれを返す。無ければ生成→保存→記録して返す。
     * variantProcessor 未注入時は IllegalStateException。
     */
    public suspend fun variant(blob: Blob, variation: Variation): Blob
}
```

生成手順（attach の順序規律に倣う：実体保存の前後で行を整合させる）:

1. `metadata.findVariant(blob.id, digest)` を引き、**あれば即返す**（再利用）。
2. 無ければ元実体を `service.get(blob.key)` で開き、`ContentSource` に包んで `variantProcessor.process(origin, variation)` を実行。
3. 結果を `spool(processed, checksum)` で一時ファイルへ落としつつ checksum / byteSize / contentType / filename を確定。
4. 派生キーを**元キーから決定的に導出**: `"<blob.key>/variants/<digest>"`（元と co-located、`KeyGenerator` は介さない＝派生物に record は不要）。
5. 派生 `Blob`（新 `BlobId`、`serviceName = service.name`）を作り、`metadata.insertVariant(blob.id, digest, variantBlob)` で **blob 行 + variant_records を 1 トランザクションで** 記録。実体 `service.put` は記録の前に行い、失敗時は記録しない（attach と同じく「実体先・行後で整合、冪等 delete 前提」）。
6. 派生 `Blob` を返す。

- 戻り値は通常の `Blob`。配信は既存 `signedReference(variantBlob, ttl)` → `resolveForDelivery` がそのまま機能する（presigned 対応サービスは Redirect、非対応は Proxy）。**新規配信コードはゼロ。**

### 4. メタデータ：`variant_records` と `MetadataStore` 拡張

スキーマ（派生 Blob は通常どおり `blobs` 表に入れ、対応関係だけ別表で持つ）:

```text
variant_records
  origin_blob_id    (FK → blobs.id, NOT NULL)
  variation_digest  (NOT NULL)
  variant_blob_id   (FK → blobs.id, NOT NULL)
  PRIMARY KEY (origin_blob_id, variation_digest)
```

`MetadataStore` ポート追加（4 メソッド）:

```kotlin
/** 既存の variant を引く。無ければ null。 */
public suspend fun findVariant(originBlobId: BlobId, variationDigest: String): Blob?

/** 派生 Blob 行と variant_records を 1 トランザクションで挿入する。 */
public suspend fun insertVariant(originBlobId: BlobId, variationDigest: String, variant: Blob)

/** ある元 Blob に紐づく全派生 Blob。カスケード削除に使う。 */
public suspend fun findVariantsOf(originBlobId: BlobId): List<Blob>

/** ある元 Blob の variant 記録と派生 Blob 行をまとめて削除する（実体削除は呼び出し側）。 */
public suspend fun deleteVariantsOf(originBlobId: BlobId)
```

Exposed アダプタ（`metadata-exposed-jdbc`）に `VariantRecords` テーブル定義と上記実装を追加し、`createSchema()` に含める。`InMemoryMetadataStore`（core テスト用フェイク）にも実装を追加。

### 5. 削除ライフサイクルとの整合（正しさの要）

派生 Blob は `attachments` 行を持たないため、既存の `findUnattachedBlobs`（参照ゼロの孤立判定）が**派生を孤立とみなして誤って回収**する。次の 2 点で防ぐ：

1. **孤立判定から派生を除外** — `findUnattachedBlobs(olderThan)` の対象から `variant_records.variant_blob_id` に出現する Blob を外す（SQL では `NOT IN (SELECT variant_blob_id FROM variant_records)` 相当）。派生は「元に従属」する所有物で、独立回収の対象にしない。
2. **元の削除に派生をカスケード** — `detach`(purge=true) と `reclaimUnattached` が元 Blob を消すとき、`findVariantsOf(originBlobId)` で派生を引き、各派生について **実体 `service.delete` → variant_records 行削除 → 派生 Blob 行削除** の順で一緒に消す。既存の「実体 → 行」順・冪等 delete 前提と同じ流儀で、途中失敗時も再実行で収束する。

これにより不変条件 **「元が消えれば派生も消える / 元が生きていれば派生は回収されない」** を保証する。`purgeRecord` は `detach` 経由なので自動的にカバーされる。

### 触れないもの

- `core` 既存オーケストレーション（attach/detach/reclaim 本体）の `java.*` 方針：変更なし。variant 追加分も純 Kotlin（変換実体は `variant-scrimage` 側）。
- `HmacReferenceSigner` / `RandomTokenKeyGenerator`：無関係、現状維持。
- 既存の配信 API（`signedReference` / `resolveForDelivery`）：派生 Blob をそのまま受けるため変更不要。

## 検証

1. **遅延生成と再利用（core, フェイク VariantProcessor）**: 同一 `(blob, variation)` で 2 回 `variant()` を呼ぶと、1 回目は `process` + `put` + `insertVariant` が走り、2 回目は記録を引くだけで `process` が呼ばれないこと。返る `Blob` が一致すること。
2. **digest の決定性**: 同じ `Transform` 列の `Variation.of(...)` が常に同じ digest を生むこと。順序・パラメータが違えば異なること（ゴールデン値）。
3. **派生は孤立回収されない**: 元に attach があり派生が存在する状態で `reclaimUnattached(now)` を呼んでも派生 Blob が残ること。
4. **カスケード削除**: 元を `detach(purge=true)` / `reclaimUnattached` で消すと、派生の実体・variant_records・派生 Blob 行がすべて消えること。`purgeRecord` でも同様。
5. **配信に乗る**: `variant()` の戻り Blob に対し `signedReference` → `resolveForDelivery` が Proxy（fs）/ Redirect（presigned）を正しく返すこと。
6. **実変換（variant-scrimage）**: 小さな実画像で Resize / Crop / Rotate / Grayscale / Convert がそれぞれ期待どおり効くこと（出力の寸法・フォーマット・グレースケール化を検証）。
7. **ABI**: `./gradlew apiDump` を全モジュールで再生成・レビュー。`core` の新 API・`MetadataStore` 追加メソッド・新モジュール `variant-scrimage` の API が反映され、その他に意図しない差分が無いこと。バージョン方針に従い minor bump。

## 実装時の注意（ブロッカーではない）

- **Scrimage の正確な座標・バージョンは実装時に確認**（推測しない）。WebP 出力にプラグインが要る場合はその依存も `variant-scrimage` に閉じ込め、ピン留めする。
- `variantProcessor` はコンストラクタの**任意引数**（既定 null）。既存利用者・非画像用途はゼロ影響だが、引数追加によりコンストラクタ ABI は変化する（minor bump で許容）。
- `Variation.digest` は base64url（キー安全・パディング無し）。決定的であれば符号化方式は内部実装であり公開しない。
- 変換は画像全体をメモリ展開する（画像処理の本質）。ストリーミングは適用しないことを `VariantProcessor` の契約に明記する。
- `findUnattachedBlobs` の除外条件追加は既存 deletion-lifecycle テストの前提を変えうるため、派生を含まないケースの挙動が不変であることも確認する。

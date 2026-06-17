# aktive-storage core の Java 依存除去（オーケストレーション層の脱 java.*）設計

- 日付: 2026-06-17
- 対象: v0.0.2 公開済み aktive-storage の `core` モジュール。`core` の**オーケストレーション層**（`AktiveStorage` / `Spool` / `Ports`）から `java.*` / `javax.*` 依存を取り除く。
- 前提: プロジェクトは現状 **JVM 専用**（共通プラグインは `org.jetbrains.kotlin.jvm` + `jvmToolchain(21)`、Kotlin 2.4.0）。KMP 化は設計ドキュメント §13 の「将来の余地」であり、本作業はそのための**前準備・衛生**であって、ビルドを KMP へ移行するものではない。

## 目的

`core` の自前ロジックを Java プラットフォーム API から切り離し、純 Kotlin（マルチプラットフォーム対応の標準ライブラリ）で書く。これにより：

1. core のオーケストレーション層が `java.*` / `javax.*` import ゼロになり、将来の KMP 移行を塞がない。
2. 暗号など標準ライブラリに相当物が無い処理は、すでに確立している**ポート + 差し替え可能な JVM デフォルト実装**の立て付けに揃える。

スコープは `core` のみ。`storage-s3` 等のアダプタは元来 JVM 依存（AWS SDK 等）であり対象外。

## 現状の Java 依存（core/src/main）

| ファイル | 依存 | 区分 |
|---|---|---|
| `AktiveStorage.kt` | `java.util.UUID`（BlobId/AttachmentId 採番） | オーケストレーション |
| `Spool.kt` | `java.util.UUID`（一時ファイル名）、`java.util.Base64`（checksum 符号化）、`java.security.MessageDigest`（MD5） | オーケストレーション |
| `Ports.kt` | なし | オーケストレーション |
| `HmacReferenceSigner.kt` | `javax.crypto.Mac` / `SecretKeySpec`、`java.security.MessageDigest`（定数時間比較）、`java.util.Base64` | ポート裏のデフォルト実装 |
| `RandomTokenKeyGenerator.kt` | `java.security.SecureRandom`、`java.util.Base64` | ポート裏のデフォルト実装 |

## 確定した方針

- **置換が容易なものは Kotlin 標準 MP API へ**: `java.util.UUID` → `kotlin.uuid.Uuid`、`java.util.Base64` → `kotlin.io.encoding.Base64`。新規依存ゼロ。Kotlin 2.4 で `@OptIn`（`ExperimentalUuidApi` 等）が要る可能性があるが、いずれも内部利用のみで公開 ABI には出ない。
- **暗号系（MD5）はポート化して JVM デフォルト実装に隔離**: Kotlin 標準に MD5 は無い。`Spool` の MD5 だけがポート裏ではなくオーケストレーションに直書きされている唯一の暗号依存なので、`Checksum` ポートとして切り出し、`MessageDigest("MD5")` をラップした JVM デフォルト実装を core 内に置く（signer/keygen と同じ立て付け）。
- **チェックサムはストリーミングを維持**: PR #7 でヒープ全展開を排除した経緯があるため、ポートは**逐次更新できる Hasher 型**とする。一括 `digest(bytes)` は content 全ロードを強いて退行するため採らない。
- **挙動互換を維持**: チェックサムは引き続き **MD5・標準 base64（パディング有り）**。`base64(md5("abc")) == "kAFQmDzST7DWlj99KOF/cg=="` を維持する。なお `S3StorageService.put` は `meta.checksum` を Content-MD5 等として送信しておらず（Blob 行に保存する内部整合性フィールドのみ）、外部契約による algorithm 固定は無い。互換は既存テストと保存値のためにのみ維持する。
- **最小変更**: `HmacReferenceSigner` / `RandomTokenKeyGenerator` は既に `ReferenceSigner` / `KeyGenerator` ポート裏の JVM アダプタであり現状維持。HMAC / SecureRandom は JVM 必須であり、無関係な Base64 移行はしない。
- **ABI 変化を許容**: `Checksum` / `Hasher` 公開インターフェース追加と `AktiveStorage` コンストラクタ引数追加で公開 API が変化する。`0.x` の minor bump で許容し `apiDump` を再生成する。

## 設計

### 1. `Ports.kt` に純 Kotlin ポートを追加

```kotlin
/** ストリーミング・チェックサム。content を一括ロードせず逐次更新する。 */
public fun interface Checksum {
    public fun newHasher(): Hasher
}

public interface Hasher {
    public fun update(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size)
    public fun digest(): ByteArray
}
```

- `Hasher.digest()` は生バイトを返す。base64 化は呼び出し側（`Spool`、Kotlin 標準 `Base64`）が行い、ポートは純粋にハッシュのみを担う。
- `update` は Kotlin 標準の `startIndex` / `endIndex` 慣習に従う。JVM デフォルト実装が `MessageDigest.update(source, startIndex, endIndex - startIndex)` へ橋渡しする。

### 2. 新規 `Md5Checksum.kt`（core 内、JVM デフォルト実装）

```kotlin
public class Md5Checksum : Checksum {
    override fun newHasher(): Hasher = object : Hasher {
        private val md = MessageDigest.getInstance("MD5")
        override fun update(source: ByteArray, startIndex: Int, endIndex: Int) {
            md.update(source, startIndex, endIndex - startIndex)
        }
        override fun digest(): ByteArray = md.digest()
    }
}
```

- `java.security.MessageDigest` の import はこのファイルに集約。ポート裏のため core オーケストレーションは汚れない。

### 3. `Spool.kt` を Java フリーに

- `java.util.UUID` → `kotlin.uuid.Uuid`（`Uuid.random()` で一時ファイル名）。
- `java.security.MessageDigest` → 注入された `Checksum`。トップレベル関数のシグネチャを `spool(content: ContentSource, checksum: Checksum): SpooledContent` に変更。
- ストリーミングループは `hasher.update(chunk, 0, n)` を逐次呼ぶ形を維持。
- `java.util.Base64` → `kotlin.io.encoding.Base64.Default`（標準アルファベット＋パディング）。`Base64.Default.encode(hasher.digest())`。

### 4. `AktiveStorage.kt`

- `java.util.UUID` → `kotlin.uuid.Uuid`（`Uuid.random().toString()` で BlobId/AttachmentId）。
- コンストラクタに `checksum: Checksum = Md5Checksum()` を追加（`keyGenerator` の後、`clock` の前）。
- `spool(content)` 呼び出しを `spool(content, checksum)` に変更。

### 触れないもの

- `HmacReferenceSigner.kt` / `RandomTokenKeyGenerator.kt`: ポート裏の JVM アダプタとして現状維持。
- `storage-fs` / `storage-s3` / `metadata-exposed-jdbc`: スコープ外（アダプタは元来 JVM）。

## 検証

1. **脱 java.* の確認**: `AktiveStorage.kt` / `Spool.kt` / `Ports.kt` に `java.` / `javax.` import がゼロであること（grep で確認）。
2. **挙動不変**: 既存テスト緑。特に `AttachTest`（`base64(md5("abc")) == "kAFQmDzST7DWlj99KOF/cg=="`）、`SpoolTest`（base64 MD5 とマルチ MB ラウンドトリップ＝ストリーミング維持）。
3. **ポートの注入性**: テスト用の偽 `Checksum` を `AktiveStorage` / `spool` に差し込めること。`Md5Checksum` が従来と同一出力（既知入力に対するゴールデン値）。
4. **ABI**: `./gradlew apiDump` を再生成・レビュー。`Checksum` / `Hasher` / `Md5Checksum` の追加とコンストラクタ変更が反映され、その他に意図しない差分が無いこと。バージョン方針に従い minor bump。

## 実装時の注意（ブロッカーではない）

- `kotlin.uuid.Uuid` / `kotlin.io.encoding.Base64` は Kotlin 2.4 でオプトインアノテーションが要る可能性がある。コンパイラ警告に従いファイル単位の `@OptIn` を付す。いずれも公開 API シグネチャには現れない（内部利用のみ）。
- `Base64.Default` の出力が `java.util.Base64.getEncoder()` と同一（標準アルファベット・パディング有り）であることを、ゴールデン値テストで担保する。

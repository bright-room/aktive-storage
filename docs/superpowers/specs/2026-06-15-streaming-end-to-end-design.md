# aktive-storage エンドツーエンド・ストリーミング 設計

- 日付: 2026-06-15
- 対象: v0.0.1 公開済み aktive-storage の put/get 経路における「ヒープ全展開」の排除（phase 2 ストリーミング）
- 前提: MVP の公開 API は `ContentSource.open(): RawSource` / `StorageService.get(): RawSource` と既にストリーム志向。本作業は**実装をエンドツーエンドでストリーミング化する**もので、公開 API のシグネチャは変えない。

## 目的

大きな blob を扱う際に、ファイル全体を JVM ヒープへ載せてしまう箇所を排除し、コンテナ/サーバーレス環境のメモリ制約下でも安定動作させる。対象は現状の 3 つのボトルネック（`spool()` / S3 `put` / S3 `get`）に限定する。FS アダプタは既にストリーミング済みのため変更しない。

## 確定した方針

- **スコープ**: ヒープ全展開の解消のみ。スプール（size+checksum を put 前に確定する仕組み）は維持し、その過程をストリーム化する。新しい公開 API 面（直接ストリーミング put 等）は追加しない（YAGNI）。
- **S3 `get` のストリーム寿命問題**: **temp ファイル方式**で橋渡しする（コルーチン・パイプ橋渡しは不採用）。
  - 根拠: アップロード経路の `spool()` が attach のたびに `SystemTemporaryDirectory` へ書き込むため、**このライブラリは既に「書き込み可能な temp ディレクトリ」を必須要件としている**。download だけゼロディスク化してもこの依存は消えず、パイプ橋渡しの主たる利点が立たない。一方で temp ファイル方式は `getObject` ブロック内で完結し、detached コルーチン・scope 所有・close 伝播といった本質的複雑さを回避できる。upload(spool) と download(get) が同じ「temp ファイルにスプール」モデルで揃い、一貫性も得られる。
  - 想定用途は「主にアップロード + presigned 配信（S3 配信は `resolveForDelivery` が Redirect を返すため `get()` を通らない）」であり、S3 `get()` の TTFB は重要要件ではない。
- **公開 ABI 変更なし**: 3 箇所すべてメソッド本体の変更のみでシグネチャは不変。`AutoCloseable` 追加もしない。BCV `.api` ベースラインの更新は不要（apiCheck は緑のまま）。

## 設計

### 1. `spool()` のストリーム化 — `core/src/main/kotlin/.../Spool.kt`

現状の `content.open().buffered().use { it.readByteArray() }`（全バイトをヒープへ展開）を**チャンクループ**へ置換する。

- `content.open().buffered()` から固定長チャンク（`ByteArray(8192)`）を `readAtMostTo(chunk, 0, size)` で繰り返し読む。
- 各チャンクで `digest.update(chunk, 0, n)`、temp ファイルの buffered sink へ `write(chunk, 0, n)`、`byteSize += n` を行う。
- EOF（`-1`）で打ち切り、`Base64(MD5)` を確定。
- ヒープ使用量はファイルサイズに依らず O(チャンクサイズ)。`SpooledContent`（temp ファイル源）と `spool()` のシグネチャは不変。

この 1 変更で、`attach()` を経由する**全アダプタ**（FS / S3 とも）のアップロード経路がヒープ展開を免れる。

### 2. S3 `put` のストリーム化 — `storage-s3/src/main/kotlin/.../S3StorageService.kt`

現状の `content.open().buffered().use { it.readByteArray() }` + `ByteStream.fromBytes(bytes)`（全展開）を、`content.open()` から**長さ既知のストリーミング `ByteStream`** を構築する形へ置換する。

- content-length は `meta.byteSize` を用いる（`spool()` で確定済み）。S3 は単一 PUT（上限 5GiB）で扱う。**multipart は将来**（>5GiB やレジューム用途。今回は YAGNI）。
- 実装手段は pin 済み smithy-kotlin runtime の正確な API を**実装時に確認**する（memory: バージョン/座標を推測しない方針）。候補と要件:
  - 要件: `content.open()`（kotlinx-io `RawSource`）を起点に、フルの `ByteArray` を作らずに streaming で本文を供給し、`contentLength = meta.byteSize` を持つ `ByteStream` を渡すこと。
  - 候補 API: `ByteStream.SourceStream` のサブクラス化（`SdkSource`/`SdkByteReadChannel` を返す）、もしくは `java.io.InputStream`（kotlinx-io `RawSource.asInputStream()`）から長さ既知の `ByteStream` を作る smithy 拡張。
  - フォールバック: 確実に streaming できる経路（InputStream ベース）を採る。いずれにせよ「フル `ByteArray` を生成しない」ことを満たす実装を選ぶ。

### 3. S3 `get` のストリーム化（temp ファイル方式）— `S3StorageService.kt`

`getObject` のレスポンスボディは SDK のコールバックスコープ内でのみ有効。これをブロック内で temp ファイルへストリーム転送し、その temp ファイルを源とする `RawSource`（close 時に temp を削除）を返す。

- `get(key)`:
  1. temp パス `aktive-s3-<UUID>.tmp` を `SystemTemporaryDirectory` 下に確保。
  2. `client.getObject(req) { resp -> resp.body を temp ファイルへ streaming 転送 }`。本文 → ファイルの転送はフル `ByteArray` を作らず行う（smithy の `ByteStream.writeToFile` 相当、または `SdkByteReadChannel`/`SdkSource` → file sink のチャンク転送。正確な API は実装時に確認）。本文 null は従来どおりエラー。
  3. temp ファイルを源とする `RawSource` を返す。
- **close で削除する `RawSource`**: `storage-s3` 内の internal クラス（例: `DeletingFileSource`）。`SystemFileSystem.source(tempFile)` をラップし、`close()` でラップ元を閉じてから `SystemFileSystem.delete(tempFile, mustExist = false)` する。
  - これは S3 固有（FS `get` は実体ファイルを返すので削除してはならない）。`core` には置かず `storage-s3` の internal に閉じる。
- コルーチン scope / `AutoCloseable` は不要。

### エラー処理・ライフサイクル

- **spool**: 既存の `cleanup()` は不変。digest/size はストリーム中に確定。
- **S3 put**: ストリーム途中で source が例外 → putObject 失敗 → `attach()` の既存ロールバック（Blob 行削除, `AktiveStorage.kt:38-41`）がそのまま機能。
- **S3 get**: 返した `RawSource` の close で temp ファイルを必ず削除。呼び出し側が close を怠ると temp ファイルが残存しうる（FS `get` がファイルハンドルを保持するのと同じ「呼び出し側が `use`/close する」契約。Proxy 配信は `use().close()` 前提）。`getObject` ブロック内の転送が失敗した場合は、その場で temp ファイルを削除してから例外を伝播。

### 公開 API への影響

- `StorageService` / `ContentSource` のシグネチャ変更なし。
- `S3StorageService` / `FilesystemStorageService` の public シグネチャ変更なし（`AutoCloseable` 追加なし）。
- **BCV `.api` ベースライン更新は不要**。apiCheck は緑のまま（このことを実装後に確認する）。

## テスト

- **core `SpoolTest`（ユニット）**: 数 MB を生成する `ContentSource`（`open()` がメモリ全展開しない `RawSource` を返す）を `spool()` に通し、得られた `SpooledContent` の `byteSize` と `checksumBase64` が、同一バイト列に対する streaming MD5 リファレンスと一致することを検証。
- **storage-s3 IT（minio / testcontainers, `S3StorageServiceIT` に追加）**:
  - 大きめ（例: 64MB）のオブジェクトを streaming な `ContentSource` から put → get で読み戻し、checksum / バイト等価を検証。
  - `get()` が返す `RawSource` を read 完了後に close すると temp ファイルが削除されること（`SystemTemporaryDirectory` 内の `aktive-s3-` プレフィックスがベースラインに戻る）を検証。
- **`EndToEndIT`（integration-tests）**: 大きめの attach → Proxy 配信（FS）往復で checksum 等価を検証し、`spool()` 経路全体がストリーミングで成立することを担保。

## 非対象（YAGNI）

- 直接ストリーミング put（呼び出し側が size+checksum を与えて temp スプールを省く API）。
- S3 multipart upload / マルチパート download。
- アップロード経路のゼロディスク化（spool 廃止）。
- コルーチン・パイプ橋渡しによる S3 get のゼロディスク・ストリーミング。

### 将来拡張の余地（今回の設計はこれらを妨げない）

ゼロディスク化が将来必要になっても、本設計は加算的に拡張できる。判断の経緯を残しておく。

- **download のゼロディスク化**: 公開シグネチャ `get(): RawSource` は temp ファイル方式でもパイプ橋渡しでも不変なため、実装の差し替え（必要なら `S3StorageService` のコンストラクタフラグでの切替）で対応でき、API 変更を伴わない。
- **upload のゼロディスク化**: spool を省くには呼び出し側が size+checksum を put 前に与える新 API が要る。これも既存 API を壊さない加算的追加。
- ただし**現状では実装価値が薄い**: (1) ハードニング構成の `/tmp` は tmpfs（RAM）が定石で、temp ファイルの実体は RAM 上のことが多くディスク懸念の多くが消える。(2) download だけゼロディスク化しても、upload 経路が spool でフルサイズを temp に書く以上、扱える blob の天井は upload 側で決まり片手落ちになる。正味の利点は巨大 S3 オブジェクトのサーバー側 TTFB に限られ、これは本作業の想定用途 (A) では重要要件ではない。実装するなら upload/download 両経路同時のゼロディスク化として、具体的ワークロードが現れた時点で行う。

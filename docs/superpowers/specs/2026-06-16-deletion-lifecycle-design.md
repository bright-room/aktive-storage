# aktive-storage 削除ライフサイクル / 孤立 Blob 回収 設計

- 日付: 2026-06-16
- 対象: v0.0.1 公開済み aktive-storage の削除経路（phase 2 「削除ライフサイクルの整理 / 孤立 Blob の回収」）
- 前提: 現状 `detach(attachment, purgeBlob=true)` は Attachment 行を消し、Blob 行＋実体を**無条件に**削除する（参照カウントしない）。`attach()` は毎回新規 Blob を採番し、既存 Blob を共有する API は無いため、**現状 Blob と Attachment は事実上 1:1**。`MetadataStore` には「Blob を参照する Attachment 数を数える」「孤立 Blob を見つける」「レコードの全添付を引く」手段が無い。

## 目的

削除を安全かつ回収可能にする。具体的には次の 3 点を満たす。

1. **参照カウント安全な purge**: 同じ Blob を複数の Attachment が参照しうる将来（設計ドキュ §7.2 が志向）に備え、`detach` の実体削除を「参照ゼロのときだけ」に限定し、宙吊り参照を構造的に防ぐ。
2. **孤立 Blob の回収**: 参照ゼロの Blob を、進行中の `attach` を巻き込まずに掃除する手段を提供する（猶予付き）。いつ走らせるかは利用者に委ねる（job 非依存）。
3. **レコード単位の一括 purge**: あるレコードの全添付（name を問わず）をまとめて参照カウント安全に外す。

設計ドキュメント §14 が初期スコープの目安に挙げる「レコード単位の一覧」「purge / purge_later」「unattached（孤立 Blob の発見）」を、ORM 抽象に通すべきポート粒度として確定させる作業でもある。

## 確定した方針

- **参照カウントを今入れる（前向き投資）**: 共有 Blob は現状 API から到達不能だが、(1) 孤立 Blob の定義（＝参照ゼロ）と purge の安全性は同じ「Blob を参照する Attachment 数」という 1 ポート操作で表せ一貫する、(2) 追加は小さい、(3) 今回の目的（§14 のポート粒度確定）に合致する。
- **猶予期間（grace period）で競合を封じる**: `attach()` は Blob 行挿入 → 実体 put → Attachment 行挿入の順で、「Attachment ゼロの Blob」には**まだ attach 途中の正規 Blob** が混ざる。「参照ゼロを全部消す」掃除は進行中 attach を巻き込む。よって孤立判定に `createdAt < olderThan` を必須とし、猶予は呼び出し側が `now - grace` として渡す。
- **job 非依存**: core は掃除を回す suspend 関数を提供するだけ。いつ・どの頻度で走らせるかは利用者（将来の integration 層）に委ねる。§9.4 の「非同期ジョブに逃がす」は、その“いつ”を利用者に委ねる形で満たす。
- **削除順序は「実体 → Blob 行」に統一**: 再実行可能性（クラッシュ耐性）のため。`StorageService.delete` が存在しないキーに冪等であることを前提とする。
- **`MetadataStore` SPI への破壊的変更を許容**: 公開インターフェースにメソッドを追加するため、外部実装者にとって破壊的。`0.x` の minor bump で許容し、`apiDump` を再生成する。影響は現状 `ExposedMetadataStore` のみ。

## 設計

### 1. `MetadataStore` ポート追加 — `core/src/main/kotlin/.../Ports.kt`

```kotlin
public interface MetadataStore {
    // 既存...
    public suspend fun insertBlob(blob: Blob)
    public suspend fun findBlob(id: BlobId): Blob?
    public suspend fun deleteBlob(id: BlobId)
    public suspend fun insertAttachment(attachment: Attachment)
    public suspend fun findAttachments(record: RecordRef, name: String): List<Attachment>
    public suspend fun deleteAttachment(id: AttachmentId)

    // 追加: ある Blob を参照する Attachment 数（参照カウント安全 purge と孤立判定の共通基盤）
    public suspend fun countAttachmentsForBlob(blobId: BlobId): Int

    // 追加: 参照ゼロ かつ createdAt < olderThan の Blob（猶予で進行中 attach を除外）
    public suspend fun findUnattachedBlobs(olderThan: Instant): List<Blob>

    // 追加: name を問わずレコードの全添付
    public suspend fun findAttachmentsForRecord(record: RecordRef): List<Attachment>
}
```

### 2. `AktiveStorage`（core）API — `core/src/main/kotlin/.../AktiveStorage.kt`

#### 2.1 `detach` の改修（参照カウント安全化）

```kotlin
public suspend fun detach(attachment: Attachment, purgeBlob: Boolean = true) {
    metadata.deleteAttachment(attachment.id)
    if (!purgeBlob) return
    // 自分の Attachment 行を消した後に数える → 残りゼロのときだけ実体+Blob行を消す
    if (metadata.countAttachmentsForBlob(attachment.blobId) > 0) return
    val blob = metadata.findBlob(attachment.blobId) ?: return
    service.delete(blob.key)      // 実体 → 行 の順（再実行可能性）
    metadata.deleteBlob(blob.id)
}
```

- 現状との差分: `deleteAttachment` の後に `countAttachmentsForBlob` を挟み、`> 0` なら Blob を残す。削除順序を「実体 → 行」に変更（現状は行 → 実体）。
- 既存コメント（「MVP は参照カウントしない」）は削除し、KDoc を実態に合わせて更新する。

#### 2.2 `reclaimUnattached`（新規・孤立掃除）

```kotlin
/**
 * 参照ゼロ かつ olderThan より前に作られた Blob を回収する。回収した件数を返す。
 * olderThan は呼び出し側が now - grace として渡し、進行中の attach を除外する。
 * いつ走らせるかは利用者の責務（このライブラリは job を持たない）。
 */
public suspend fun reclaimUnattached(olderThan: Instant): Int {
    val orphans = metadata.findUnattachedBlobs(olderThan)
    for (blob in orphans) {
        service.delete(blob.key)   // 実体 → 行 の順（冪等 delete 前提で再実行可能）
        metadata.deleteBlob(blob.id)
    }
    return orphans.size
}
```

- 途中失敗は再実行で収束する: 実体削除後・行削除前にクラッシュしても、次回の掃除で同じ Blob を再発見し、`service.delete` は存在しないキーに冪等な no-op となり、行削除まで進んで収束する。

#### 2.3 `purgeRecord`（新規・レコード単位）

```kotlin
/** レコードの全添付（name 問わず）を参照カウント安全に detach+purge する。 */
public suspend fun purgeRecord(record: RecordRef) {
    for (attachment in metadata.findAttachmentsForRecord(record)) {
        detach(attachment, purgeBlob = true)
    }
}
```

- `detach` を再利用するため、共有 Blob を複数の Attachment が参照していても各 Blob は参照ゼロになった時点でのみ purge される。

### 3. `ExposedMetadataStore` の実装 — `metadata-exposed-jdbc/.../ExposedMetadataStore.kt`

追加 3 メソッドを Exposed で実装する。`dbQuery`（`Dispatchers.IO` + `transaction`）の既存パターンに従う。

- `countAttachmentsForBlob(blobId)`: `AttachmentsTable.selectAll().where { blobId eq ... }.count()`（または `Count` 集約）。`Int` へ収める。
- `findUnattachedBlobs(olderThan)`: `BlobsTable` を起点に「`AttachmentsTable` に当該 blobId を参照する行が存在しない」かつ `BlobsTable.createdAt < olderThan.toEpochMilliseconds()` で抽出。実装は LEFT JOIN + `AttachmentsTable.id.isNull()`、もしくは `notExists`/`NOT IN` サブクエリ。正確な Exposed v1 の式 API は実装時に確認する（memory: バージョン/座標を推測しない方針）。
- `findAttachmentsForRecord(record)`: `AttachmentsTable.selectAll().where { (recordType eq ...) and (recordId eq ...) }.map { it.toAttachment() }`。

`createdAt` は epoch millis（BIGINT）保持で、`Instant` と相互変換する既存規約に従う。

### 4. `StorageService.delete` の冪等性確認

「実体 → 行」順の再実行可能性は `delete` の冪等性に依存する。

- **S3**: `deleteObject` は存在しないキーでも成功扱い。冪等。変更不要。
- **FS** (`FilesystemStorageService`): `delete` が存在しないファイルでエラーにならないこと（`SystemFileSystem.delete(path, mustExist = false)` 相当）を確認し、必要なら修正する。`Ports.kt` の `delete` KDoc に「存在しないキーに対しては no-op（冪等）」の契約を明記する。

## エラー処理・ライフサイクル

- 掃除（`reclaimUnattached`）/ `purgeRecord` の途中失敗は**例外を伝播**する（握りつぶさない）。それまでに削除済みの分は確定し（部分回収を許容）、未処理分は次回の再実行で処理される。冪等 delete 前提で再実行は収束する。戻り値の回収件数は「実体＋行の削除まで完了した件数」を表す。
- `findUnattachedBlobs` の `olderThan` 猶予で、進行中 attach の Blob を構造的に除外する。
- `detach(purgeBlob=true)` で `countAttachmentsForBlob > 0` のときは Blob も実体も触れない（共有参照の保護）。

## 公開 API / ABI への影響

- `MetadataStore` に 3 メソッド追加（**SPI 破壊的変更**。`0.x` minor bump で許容）。
- `AktiveStorage` に public メソッド 2 つ追加（`reclaimUnattached`, `purgeRecord`）。`detach` のシグネチャは不変（本体のみ変更）。
- `core` と `metadata-exposed-jdbc` の `.api` ベースライン更新が必要（`./gradlew apiDump`）。実装後に `apiCheck` が緑であることを確認する。

## テスト

- **core ユニット（フェイク `MetadataStore` / `StorageService`）**:
  - `detach(purgeBlob=true)`: `countAttachmentsForBlob` が `> 0` を返すケースで `service.delete` / `deleteBlob` が**呼ばれない**こと。`0` を返すケースで両方呼ばれ、かつ「実体 → 行」の順であること。
  - `detach(purgeBlob=false)`: Attachment のみ削除、Blob に触れないこと。
  - `reclaimUnattached(olderThan)`: フェイクが返す孤立リストの各 Blob について `service.delete` → `deleteBlob` が呼ばれ、回収件数が返ること。
  - `purgeRecord(record)`: `findAttachmentsForRecord` が返す全添付について `detach` が呼ばれること。
- **`ExposedMetadataStore` テスト（実 DB）**:
  - `countAttachmentsForBlob`: 同一 blobId を参照する Attachment を 2 件挿入 → `2`。1 件削除 → `1`。0 件 → `0`。
  - `findUnattachedBlobs(olderThan)`: 「古い孤立 Blob」「新しい(猶予内)孤立 Blob」「紐付き Blob」を用意し、**古い孤立のみ**返ること。
  - `findAttachmentsForRecord`: 同一レコードに異なる name の添付を複数挿入 → 全件返ること。別レコードは含まないこと。
- **`EndToEndIT`（integration-tests, S3+PostgreSQL）**:
  - attach → `detach(purgeBlob=true)` で実体と Blob 行が消えること。
  - `purgeBlob=false` で残した Blob が孤立として `reclaimUnattached(now)` で回収されること（実体も消える）。
  - `purgeRecord` で 1 レコードの複数添付が一括で消えること。

## 非対象（YAGNI）

- 非同期ジョブ機構そのもの（スケジューラ / ワーカー）。core は suspend 関数を提供するのみ。
- 参照カウントを増やす経路（既存 Blob を共有して attach する API）。本設計は共有が来ても安全に purge できる土台を用意するが、共有 attach API 自体は別作業。
- 削除のソフトデリート / ゴミ箱（論理削除）。今回は物理削除のみ。
- Variant（派生画像）の回収。Variant 層は未実装のため対象外。

# 履歴・設定の同期アーキテクチャ

セッション履歴を端末をまたいで保持・閲覧し、設定をウォッチとスマートフォンの双方から変更できるようにする。

## 構成要素とデータフロー

```
[watch アプリ] ←Wearable Data Layer→ [phone アプリ (:mobile)] ←Firestore SDK→ [Firestore]
                                              ↑ Firebase Auth (Google サインイン)
```

- **watch は Firestore と直接通信しない**。phone がブリッジになる (電池・接続安定性・認証 UI の都合)。
- **watch → phone (履歴)**: セッション完了時に SessionRecord を DataItem (`/sessions/<id>`) として書く。phone は受信して Firestore へアップロードし、成功したら DataItem を削除する (削除が ack を兼ねる)。watch はローカルにも直近履歴を保持する。
- **設定の双方向同期**: 設定全体を 1 つの DataItem (`/settings`) に持ち、`updatedAtMs` の**最終更新者勝ち (LWW)** で解決する。どちらの端末も「自分の変更を書く」「相手の変更を受けて updatedAtMs が新しければ適用する」だけ。phone はさらに Firestore の `users/{uid}/settings/current` を LWW で同期する (複数スマホ・機種変更に備えた正本は Firestore)。
- **メニュー/プログラムの双方向同期**: カスタムメニュー・プログラムと選択状態 (`LibraryDocument`) も設定と同じ構造で同期する (DataItem `/library`、Firestore `users/{uid}/library/current`、文書全体 LWW)。phone での編集と watch での選択 (開始画面) がそれぞれ更新者になる。プリセットは同期対象外 (両端末がプロファイルから生成する)。
- **認証**: phone アプリは初回に Google サインイン (Firebase Auth)。Firebase SDK が認証コンテキストを Firestore 呼び出しに自動で乗せ、Security Rules が uid 一致を検証する。

## インフラ (Spark プラン = 無料)

- **Firebase Spark プラン (無料)**: Firebase Auth + Firestore + Firestore Security Rules。
- 課金は一切しない。billing アカウントはプロジェクトにリンクしない。
- **Firestore Security Rules がアクセス制御と LWW を強制する** (`firestore.rules`)。サーバ層は持たず、phone から Firestore SDK で `users/{uid}` 配下を直接読み書きする。

## 認証の拡張余地

- ユーザーのキーは **Firebase Auth の uid**。Google 以外のプロバイダを将来追加する場合も Firebase の account linking で同一 uid に集約されるため、データ構造は変わらない。
- `users/{uid}` ドキュメントに `providers` 配列 (例 `["google.com"]`) を保持し、どの認証手段が紐づいているかをデータ側にも残す。

## Firestore スキーマ

ドキュメント本体は `:core` の共有モデル (kotlinx-serialization) を JSON 化した `json` 文字列フィールドで持ち、並び替え・LWW 判定に使う数値だけを別フィールドに併置する (モデル進化時に Rules のフィールド対応を直さなくて済むようにするため)。

```
users/{uid}                          # プロファイル
  providers: string[]                # 紐づく認証プロバイダ
  createdAtMs, updatedAtMs: number

users/{uid}/sessions/{sessionId}     # セッション履歴 (append-only。delete は Rules で禁止)
  startedAtMs: number                # 並べ替え用
  json: string                       # SessionRecord 全体の JSON

users/{uid}/settings/current         # 設定の正本 (LWW: Rules が updatedAtMs 単調増加を強制)
  updatedAtMs: number                # LWW 判定用
  json: string                       # SettingsDocument 全体の JSON

users/{uid}/library/current          # メニュー/プログラムと選択状態の正本 (settings と同じ LWW)
  updatedAtMs: number                # LWW 判定用
  json: string                       # LibraryDocument 全体の JSON

users/{uid}/workouts/{workoutId}     # 筋トレ記録 (分析用バックアップ。delete は Rules で禁止)
  startedAtMs: number                # 並べ替え用
  json: string                       # WorkoutRecord 全体の JSON。進行中もセット入力毎に upsert

users/{uid}/strengthCatalog/current  # ジム/トレーニング台帳のバックアップ (library と同じ LWW 形)
  updatedAtMs: number                # LWW 判定用
  json: string                       # StrengthCatalog 全体の JSON
```

- `sessionId` は watch が生成する (`<startedAtMs>-<乱数>`)。アップロードは set による冪等 upsert で、再送しても重複しない。
- `workoutId` は phone が生成する (同じ `<startedAtMs>-<乱数>` 形式)。workout と strengthCatalog は phone ローカルが正本で、Firestore は分析用バックアップとして書き込み専用 (アプリは読み戻さない)。
- `json` 文字列の中身の妥当性は Rules では検証しない (本人だけが書ける領域なので、破損しても自分の履歴/設定が壊れるだけ)。サイズ上限だけ Rules で持つ。

## Firestore Security Rules (`firestore.rules`)

- `users/{uid}/**` は `request.auth.uid == uid` のときだけ read/write 可。他人のデータには到達できない。
- `sessions/{id}` / `workouts/{id}` は create/update のみ許可、delete は禁止 (append-only 履歴)。
- `settings/current` の update は `request.resource.data.updatedAtMs > resource.data.updatedAtMs` のときだけ許可 (LWW を Rules で強制)。古い更新は PermissionDenied になり、クライアントはサーバ値を読み直して反映する。
- Rules の deploy は `firebase deploy --only firestore:rules` (`deploy-firestore-rules` workflow が main push で自動実行する)。
- Rules の挙動は `rules-test/` の Firebase Emulator 自動テストで CI 上検証してから deploy する (uid 一致 / append-only / LWW / 未認証拒否をコレクションごとにカバー)。

## モジュール構成

- `:core` — SessionRecord 等の共有モデル (kotlinx-serialization)。watch / mobile が依存する
- `:app` (watch) / `:mobile` (phone) — 同一 applicationId。Play では同一アプリの別フォームファクター

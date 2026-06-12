# 履歴・設定の同期アーキテクチャ

セッション履歴を端末をまたいで保持・閲覧し、設定をウォッチとスマートフォンの双方から変更できるようにする。

## 構成要素とデータフロー

```
[watch アプリ] ←Wearable Data Layer→ [phone アプリ (:mobile)] ←REST/HTTPS→ [server (:server, ktor)] → [Firestore]
                                              ↑ Firebase Auth (Google サインイン)
```

- **watch はサーバーと直接通信しない**。phone がブリッジになる (電池・接続安定性・認証 UI の都合)。
- **watch → phone (履歴)**: セッション完了時に SessionRecord を DataItem (`/sessions/<id>`) として書く。phone は受信してサーバーへアップロードし、成功したら DataItem を削除する (削除が ack を兼ねる)。watch はローカルにも直近履歴を保持する。
- **設定の双方向同期**: 設定全体を 1 つの DataItem (`/settings`) に持ち、`updatedAtMs` の**最終更新者勝ち (LWW)** で解決する。どちらの端末も「自分の変更を書く」「相手の変更を受けて updatedAtMs が新しければ適用する」だけ。phone はさらにサーバーの `/v1/settings` と同じ LWW で同期する (複数スマホ・機種変更に備えた正本はサーバー)。
- **認証**: phone アプリは初回に Google サインイン (Firebase Auth)。サーバー API は `Authorization: Bearer <Firebase ID トークン>` を必須とし、検証済み uid のデータだけを読み書きする。

## インフラ (無料構成)

- **Firebase Spark プラン (無料)**: Firebase Auth + Firestore。
- **server は Cloud Run** (無料枠) を一次候補とし、コンテナ (Dockerfile) として任意の PaaS で動かせる形に保つ。Cloud Run は請求先アカウントの登録が必要だが無料枠内で運用する。
- **Firestore のセキュリティルールはクライアント直アクセスを全拒否**する。書き込み経路はサーバー (Admin SDK) のみ。

## 認証の拡張余地

- ユーザーのキーは **Firebase Auth の uid**。Google 以外のプロバイダを将来追加する場合も Firebase の account linking で同一 uid に集約されるため、データ構造は変わらない。
- `users/{uid}` ドキュメントに `providers` 配列 (例 `["google.com"]`) を保持し、どの認証手段が紐づいているかをデータ側にも残す。

## Firestore スキーマ

```
users/{uid}                          # プロファイル
  providers: string[]                # 紐づく認証プロバイダ
  createdAtMs, updatedAtMs: number

users/{uid}/sessions/{sessionId}     # セッション履歴 (immutable)
  schema: 1
  startedAtMs, durationSec: number
  cycles, plannedCycles: number
  fatigueBrake: boolean
  calories: number | null
  zoneRatio: number | null
  highDurationsSec: number[]         # per-cycle 高強度所要時間 (体力トレンドの源泉)
  avgBpm, maxBpm: number | null
  config: { upperBpm, lowerBpm, targetCycles, fatigueRatio,
            minBaselineSec, highTimeoutSec, recoveryTimeoutSec }
  device: "watch"

users/{uid}/settings/current         # 設定の正本 (LWW)
  upperBpm, lowerBpm, targetCycles, fatigueRatio,
  minBaselineSec, highTimeoutSec, recoveryTimeoutSec: number
  updatedAtMs: number
  updatedBy: "watch" | "phone"
```

- `sessionId` は watch が生成する (`<startedAtMs>-<乱数>`)。アップロードは PUT による冪等 upsert で、再送しても重複しない。

## server API (:server, ktor)

すべて要認証。uid はトークンから取り、パスにユーザー ID を含めない。

| エンドポイント | 動作 |
|---|---|
| `PUT /v1/sessions/{id}` | セッション記録の冪等 upsert |
| `GET /v1/sessions?limit=N` | 新しい順の履歴一覧 |
| `GET /v1/settings` | 設定の正本を返す (未設定なら 404) |
| `PUT /v1/settings` | LWW: 送信された `updatedAtMs` が保存値より新しいときだけ反映。常に最新の正本を返す |

## モジュール構成

- `:core` — SessionRecord 等の共有モデル (kotlinx-serialization)。watch/phone/server すべてが依存する
- `:app` (watch) / `:mobile` (phone) — 同一 applicationId。Play では同一アプリの別フォームファクター
- `:server` — ktor。Firestore へのアクセスは interface で抽象化し、ユニットテストは in-memory 実装で行う

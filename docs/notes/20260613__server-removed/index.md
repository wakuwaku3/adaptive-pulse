# server (Cloud Run) を撤廃して Firestore-only に倒した

2026-06-13

## 背景

- 初版の同期スタック (`5cb2f60`) で ktor + firebase-admin の `:server` モジュールを立て、Firestore へのアクセスを Admin SDK に寄せる設計を採用した。Firestore Security Rules は「クライアント直アクセス全拒否」。
- 防御的な選択で、強い具体的根拠 (Rules では書きづらい invariant が想定されていた、等) は無かった。
- 直近で `infra/` を Terraform 化し Cloud Run + Artifact Registry + tfstate バケットを立てた段階で「Spark で完結する前提が崩れている」「AR 無料枠 0.5 GB を deploy 数回で超える」「tfstate バケットの GCS 無料枠は US 限定」等のじわじわ課金が顕在化。

## 意思決定

ユーザ確認:
- 今後もコストはかけない (=Spark プランで完結させる)。
- server 層に置きたい固有の invariant は無い。将来のサーバ計算 (rate limit, AI 解析等) も予定無し。

→ server 層を撤廃し、phone を Firestore SDK 直叩きに倒す。Security Rules がアクセス制御 (uid 一致) と LWW (settings の `updatedAtMs` 単調増加) を強制する唯一の防衛線になる。

## やったこと

- `firestore.rules` を書き直し (uid スコープ + sessions append-only + settings LWW)。
- `:server` モジュールを削除、`mobile` を Firestore SDK 直叩き (`FirestoreSync.kt`) に refactor。`ApiClient` (ktor client) と `SERVER_BASE_URL` BuildConfig は廃止。
- `infra/` (Terraform)・`terraform.yml`・`deploy-server.yml` を削除。
- `deploy-firestore-rules.yml` workflow と `scripts/setup-firebase-rules-deploy.sh` (firebaserules.admin のみ持つ最小 SA を作る slim bootstrap) を追加。
- Cloud Run / AR / runtime SA / tf-runner SA / tfstate バケットを gcloud で削除し、billing アカウントを project から unlink して Spark に戻す。

## 受け入れた trade-off

- **検証ロジックの置き場所**: Kotlin/ktor で書けていた validation が Rules の CEL 風 DSL に降りた。json 文字列の中身は Rules では検証されない (本人だけが書ける領域なので破損しても自分のデータが壊れるだけ)。
- **将来のサーバ計算**: rate limit / 派生データ生成 / 複数ユーザ集計 が欲しくなったら server 層を戻す必要がある。それまでは無料で回す方を優先。
- **過去 image rollback の喪失**: AR ごと消したので Cloud Run の過去 revision に戻る経路は無い。Cloud Run 自体無いので moot だが、今後 server を戻すなら考え直す。

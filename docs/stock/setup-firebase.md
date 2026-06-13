# Firebase / GCP セットアップ手順 (人間の作業)

履歴・設定同期 (docs/stock/sync.md) を有効にするための一回限りのセットアップ。
すべて無料枠 (Firebase Spark + Cloud Run 無料枠) で収まる。Cloud Run のみ請求先アカウントの登録が必要。

## 1. Firebase プロジェクト

1. https://console.firebase.google.com で プロジェクトを作成 (例: `adaptive-pulse`)
2. **Authentication → Sign-in method → Google を有効化**
3. **Firestore Database を作成** (本番モード、リージョンは `asia-northeast1` 推奨)
4. Firestore の **ルール**にリポジトリの `server/firestore.rules` の内容を貼り付けて公開
   (クライアント直アクセス全拒否。アクセスはサーバーの Admin SDK のみ)

## 2. Android アプリ登録 (phone アプリの Google サインイン)

1. Firebase Console → プロジェクトの設定 → 「アプリを追加」→ Android
   - パッケージ名: `io.github.wakuwaku3.adaptivepulse`
2. **SHA-1 フィンガープリントを登録** (debug と release の両方):
   ```bash
   # debug (Android が自動生成する鍵)
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
   # release (upload key)
   keytool -list -v -keystore ~/keystores/adaptive-pulse-upload.jks -alias adaptive-pulse | grep SHA1
   ```
3. `google-services.json` をダウンロードして **`mobile/google-services.json`** に置く
   (gitignore 済み。無くてもビルドは通るが、サインインできない)

## 3. server のデプロイ (Cloud Run, Terraform)

GCP リソース (Cloud Run / Artifact Registry / 実行 SA / IAM) は `infra/` の Terraform が管理する。卵-鶏 (tfstate バケットと Terraform 実行 SA) だけはローカルの bootstrap で一度だけ作る。

### 3.1 一度だけ手元で実行 (bootstrap)

1. https://console.cloud.google.com で Firebase と同じプロジェクトを開き、請求先アカウントを設定
2. `gcloud auth login` で人間アカウントでサインイン
3. リポジトリ直下で:
   ```bash
   devbox shell   # gcloud / terraform / gh が PATH に通る
   bash infra/bootstrap.sh <GCP プロジェクトID>
   ```
   bootstrap が API 有効化・`<PROJECT>-tfstate` バケット作成・Terraform 実行 SA (`tf-runner`) と Owner ロール付与・SA キー生成までを行い、GitHub に `GCP_SA_KEY` (secret) と `GCP_PROJECT_ID` / `GCP_REGION` / `TFSTATE_BUCKET` (variables) を登録する。
4. (任意) GitHub の Actions タブから **terraform workflow を `workflow_dispatch` で実行** するか、`infra/` を変更して main へ push すると CI が `terraform apply` する。
5. apply 完了後、Cloud Run の URL を `SERVER_BASE_URL` に設定:
   ```bash
   url=$(cd infra && GOOGLE_APPLICATION_CREDENTIALS=... \
     terraform init -backend-config="bucket=$(gh variable get TFSTATE_BUCKET)" >/dev/null && \
     terraform output -raw service_url)
   gh variable set SERVER_BASE_URL --body "$url"
   ```
   - terraform output だけ取り出せれば良いので、CI のログ Summary に出る URL をコピペして `gh variable set SERVER_BASE_URL --body 'https://...'` でも構わない。

### 3.2 以降の運用

- `server/` の変更 → `deploy-server` workflow がコンテナを Artifact Registry に push して Cloud Run を更新する (Terraform 管理外の image だけが更新される)。
- `infra/` の変更 → `terraform` workflow が plan/apply する。PR では plan を本文にコメント、main push で apply。
- AR は cleanup policy で最新 1 image のみ保持し、0.5 GB-月 無料枠を維持する (`infra/main.tf`)。古い image での rollback は諦め、必要なら git から再ビルドする。
- 最小権限化 (`tf-runner` の Owner を細分化) は将来課題。

## 4. phone アプリにサーバー URL を設定

- ローカルビルド: リポジトリ直下の `gradle.properties` ではなく **`~/.gradle/gradle.properties`** に
  `adaptivePulse.serverUrl=https://...` を書く (リポジトリにコミットしない)
- リリースビルド (GitHub Release): bootstrap 後の手順 3.1 §5 で `SERVER_BASE_URL` を設定済みになる

## 5. 動作確認

1. phone アプリを起動 → Google サインイン
2. watch でセッションを完走 → phone の HISTORY に出る (Firestore Console でも確認可能)
3. phone の SETTINGS で値を変更 → watch の設定画面に反映される (逆方向も同様)

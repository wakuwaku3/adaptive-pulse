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

## 3. server のデプロイ (Cloud Run)

1. https://console.cloud.google.com で Firebase と同じプロジェクトを開き、請求先アカウントを設定
2. Artifact Registry にリポジトリ `adaptive-pulse` (Docker, リージョン `asia-northeast1`) を作成
3. サービスアカウントを作成し、ロール「Cloud Run 管理者 / Artifact Registry 書き込み / サービス アカウント ユーザー」を付与し、JSON キーをダウンロード
4. GitHub リポジトリに設定:
   ```bash
   gh secret set GCP_SA_KEY < サービスアカウントキー.json
   gh variable set GCP_PROJECT_ID --body "<プロジェクトID>"
   gh variable set GCP_REGION --body "asia-northeast1"
   gh variable set ENABLE_SERVER_DEPLOY --body "true"
   ```
5. main へ push (server/ 配下の変更) すると deploy-server workflow が Cloud Run へデプロイする
6. デプロイ後の URL (例 `https://adaptive-pulse-server-xxxx.a.run.app`) を控える
   - Cloud Run の「認証が必要」設定は **オフ (未認証呼び出しを許可)** にする
     (API 自体が Firebase ID トークンで認証する)

## 4. phone アプリにサーバー URL を設定

- ローカルビルド: リポジトリ直下の `gradle.properties` ではなく **`~/.gradle/gradle.properties`** に
  `adaptivePulse.serverUrl=https://...` を書く (リポジトリにコミットしない)
- リリースビルド (GitHub Release): `gh variable set SERVER_BASE_URL --body "https://..."`

## 5. 動作確認

1. phone アプリを起動 → Google サインイン
2. watch でセッションを完走 → phone の HISTORY に出る (Firestore Console でも確認可能)
3. phone の SETTINGS で値を変更 → watch の設定画面に反映される (逆方向も同様)

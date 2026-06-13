# Firebase セットアップ手順 (人間の作業)

履歴・設定同期 (docs/stock/sync.md) を有効にするための一回限りのセットアップ。
すべて Firebase Spark プラン (無料) で収まる。billing アカウントはリンクしない。

## 1. Firebase プロジェクト

1. https://console.firebase.google.com で プロジェクトを作成 (例: `adaptive-pulse`)
2. **Authentication → Sign-in method → Google を有効化**
3. **Firestore Database を作成** (本番モード、リージョンは `asia-northeast1` 推奨)
4. Firestore の **ルール** は次節の Rules deploy で `firestore.rules` から自動配信する

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
4. **CI の release ビルドにも embed する** (これをやらないと sideload した release APK で「Firebase is not configured」になる):
   ```bash
   base64 -w0 mobile/google-services.json | gh secret set MOBILE_GOOGLE_SERVICES_JSON
   ```
   release workflow が secret を base64 decode して `mobile/google-services.json` に復元してから gradle build する。

## 3. Firestore Rules deploy のセットアップ

Rules は phone クライアントの直接書き込みに対する唯一の防衛線なので、CI で確実に反映する。専用 SA (firebaserules.admin だけ持つ最小権限) を一度作る。

### 3.1 一度だけ手元で実行 (bootstrap)

1. `gcloud auth login` で人間アカウントでサインイン
2. リポジトリ直下で:
   ```bash
   devbox shell   # gcloud / gh が PATH に通る
   bash scripts/setup-firebase-rules-deploy.sh <Firebase プロジェクト ID>
   ```
   bootstrap が API 有効化 (firebaserules, firestore, iam)・SA `firebase-rules-deployer` 作成・`roles/firebaserules.admin` 付与・SA キー生成までを行い、GitHub に `FIREBASE_RULES_DEPLOYER_KEY` (secret) と `FIREBASE_PROJECT_ID` (variable) を登録する。
3. 初回 Rules deploy: GitHub の Actions タブから **deploy-firestore-rules workflow を `workflow_dispatch` で実行**。以降は `firestore.rules` 変更を main へ push すると CI が deploy する。

### 3.2 以降の運用

- `firestore.rules` を変更 → `deploy-firestore-rules` workflow が `rules-test/` の Emulator テストで挙動を検証し、PR 中は test のみ、main push で test pass → deploy。
- ローカルで Rules テストを回す: `cd rules-test && npm ci && npm test` (devbox に node が入っていれば動く)。

## 4. 動作確認

1. **phone 実機に sideload**: `bash scripts/sideload_mobile.sh` (最新 release の署名済 APK を `gh release download` + `adb install`。詳細は script ヘッダ)
2. phone アプリを起動 → Google サインイン
3. watch でセッションを完走 → phone の HISTORY に出る (Firestore Console でも確認可能)
4. phone の SETTINGS で値を変更 → watch の設定画面に反映される (逆方向も同様)

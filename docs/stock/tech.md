# 技術選定

## アプリ

- **言語/UI**: Kotlin / Compose for Wear OS
- **applicationId**: `io.github.wakuwaku3.adaptivepulse` (予定)
- **心拍取得**: Health Services API の **ExerciseClient**。セッションをワークアウトとして扱え、Foreground Service + Ongoing Activity・画面オフ中のセンサー継続取得と整合するため、単発計測向けの MeasureClient ではなくこちらを採る。
- **フェーズ通知**: Vibrator API (イベント別の振動パターン)
- **設定永続化**: DataStore (Preferences)
- **ロジック分離**: フェーズ遷移・サイクルカウント・疲労ブレーキは Android 非依存の純 Kotlin ステートマシンとして実装し、時計と心拍ソースを interface で注入する。JVM 単体テストで検証する (`.claude/rules/kotlin.md`)。
- **(拡張)** Health Connect SDK (セッション書き込み) / Android BLE API (Polar H10、標準 Heart Rate Service 0x180D)

## 開発環境

すべて WSL 内で完結させる (Windows 側 Android Studio は使わない。AI 駆動開発の主経路を CLI に揃えるため。選定経緯: `docs/notes/20260611__kickoff/`)。

- devbox + direnv で JDK 等の CLI ツールを再現する (`.claude/rules/devbox.md`)。
- Android SDK は `scripts/setup_android.sh` で `~/Android/Sdk` に導入する (cmdline-tools / platform-tools / platforms / build-tools / emulator / Wear OS system image / AVD `adaptivepulse_wear`)。`.envrc` が `ANDROID_HOME` と PATH を通す。
- ビルドは `./gradlew` (wrapper)。モジュール構成は `:core` (純 Kotlin ドメインロジック) + `:app` (Wear OS アプリ)。
- エミュレータは WSL 内で実行する (/dev/kvm + nested virtualization、画面は WSLg)。ユーザが `kvm` グループに属している必要がある。素の Ubuntu には qemu/Qt が要求するシステムライブラリが無いため apt で導入する (不足分は `scripts/setup_android.sh` が検出して案内する)。
- 実機 (Pixel Watch) へは WSL から Wi-Fi ADB で直接サイドロードする。
- CI: GitHub Actions (public repo)。構成は `.claude/rules/feedback-loop.md`。

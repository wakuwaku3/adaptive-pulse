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

- devbox + direnv で JDK 等の CLI ツールを再現する (`.claude/rules/devbox.md`)。
- Android SDK / エミュレータの導入方法 (WSL 内 cmdline-tools か Windows 側 Android Studio か) はアプリ scaffold 時に決める (検討項目: `docs/notes/20260611__kickoff/`)。
- CI: GitHub Actions (public repo)。構成は `.claude/rules/feedback-loop.md`。

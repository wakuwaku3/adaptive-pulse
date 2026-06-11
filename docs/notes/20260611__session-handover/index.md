# 2026-06-11 セッション引き継ぎ: WSL 再起動 (kvm グループ追加) を挟む再開手順

## いまどこにいるか

エミュレータ検証の直前。コード・環境はすべて準備済みで、残るは kvm 権限のみ。

- 要件・技術選定: 確定済み (`docs/stock/requirements.md` / `tech.md`)。経緯は `docs/notes/20260611__kickoff/`
- `:core`: IntervalEngine (ステートマシン) 実装済み、単体テスト 12 件オールグリーン
- `:app`: セッション UI (開始→心拍/フェーズ/サイクル表示→終了)、合成心拍ソース、振動マッピングまで実装済み。`./gradlew build` 成功
- Android SDK: `~/Android/Sdk` 導入済み (`scripts/setup_android.sh`)。Wear OS イメージ (android-34) + AVD `adaptivepulse_wear` 作成済み
- CI: グリーン (gitleaks 全履歴 / docs 配置 / gradle build)
- **未完了**: ユーザの kvm グループ追加 (`sudo usermod -aG kvm takushi` 実行) → WSL 再起動。これが本引き継ぎの理由

## 再起動後の最初の手順

1. `groups | grep kvm` で所属確認 (無ければ usermod からやり直し)
2. `scripts/run_emulator.sh` でエミュレータ起動 (WSLg に画面が出る。GPU エラーなら `-gpu swiftshader_indirect` を付ける)
3. `./gradlew :app:installDebug` (または `adb install app/build/outputs/apk/debug/app-debug.apk`)
4. ウォッチ画面で AdaptivePulse を起動 →「開始」
5. **確認したいこと**: 合成心拍が高強度で上昇→155 超で振動+回復へ→120 台へ下降→140 未満で振動+高強度へ、がサイクル表示とともに回り、7 サイクルで「おつかれさま」になること

## 実装上の注意 (次セッションの自分へ)

- 合成心拍ソース (`SyntheticHeartRateSource`) はフェーズを見てペースを変える「従順な人間」のシミュレータ。タイムアウト・疲労ブレーキは通常経路では発火しないので、見たければ `SessionConfig` のデフォルトを一時的に狭めるか、ソースの漸近係数を変える
- `SessionViewModel` は薄い glue として単体テストを書いていない (core テストが正しさを担保し、glue はエミュレータで目視確認する方針)。glue が育ったら service-level テストを検討
- 振動パターンはエミュレータでは体感確認できない (実機調整前提のプレースホルダ)

## エミュレータ確認後の次ステップ (順序案)

1. Health Services ExerciseClient 実装 (`HeartRateSource` の実装を 1 つ追加するだけの構造になっている)。エミュレータの合成心拍センサーと接続して確認。BODY_SENSORS の実行時パーミッション要求もここで
2. Foreground Service + Ongoing Activity 化 (画面オフ中の計測継続。requirements の非機能要件)
3. DataStore で SessionConfig を永続化
4. 残課題は `docs/notes/20260611__kickoff/` の「残課題 (open)」参照

## ハマりどころメモ

- `yes | sdkmanager` は pipefail で SIGPIPE 141 → `(yes || true) |`
- :core に jvmTarget 17 を指定するなら java 側 (`java { source/targetCompatibility }`) も揃える (devbox JDK は 21)
- gitleaks-action は初回 push で壊れる → CI は gitleaks バイナリ直接実行に変更済み

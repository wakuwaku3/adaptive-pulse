# 2026-06-11 キックオフ: 要件確定と repo 立ち上げ

## 経緯

別環境で作成した引き継ぎドキュメント (心拍トリガーのアダプティブ・インターバルアプリ構想) をもとに開発を開始。引き継ぎ時点の確定内容は `docs/stock/requirements.md` に反映済みのため、ここには**今日決めたこと・議論の経緯・残課題**だけを残す。

## アプリ名の決定

候補: PulseShift / ZoneShift / HeartGate / AdaptivePulse。
→ **AdaptivePulse** (repo: `adaptive-pulse`) を採用。「アダプティブなインターバル」という設計思想をそのまま名前にした。

## 要件の論点と決定

### 1. 疲労ブレーキの基準時間 (仕様の構造的欠陥の修正)

引き継ぎ仕様の「初回サイクルの半分以下で疲労判定」には欠陥があった: 初回サイクルは低心拍 (安静〜中強度) から 155 まで上げるため構造的に長く、2 サイクル目以降は 140 スタートで短い。疲労がなくても 2 サイクル目から誤発動しうる。

検討した案:

- (a) 全サイクルの高強度所要時間を「140 を上向きに超えてから 155 到達まで」で統一して測る
- (b) 初回をウォームアップ扱いで除外し 2 サイクル目を基準にする
- (c) 仕様のまま

→ **(a) を採用**。ウォームアップ区間が計測から自然に除外され、全サイクルが同じ区間 (140→155) で比較できる。「1 サイクル目かどうか」の判定に固定時間は不要 (ステートマシンがサイクル番号を知っている)。

残るエッジケース「筋トレ直後で開始時点から心拍がほぼ 140」は、初回の計測値が**最低基準時間 (デフォルト 45 秒、設定可能) 未満なら 2 サイクル目を基準にする**ガードで対処。

### 2. 閾値初期値の自動導出 (ユーザ要望)

155/140 は本人の体感ベースなので、身体属性から初期値を自動導出したいという要望。

- 心拍ゾーンを決める生理学的主因は**年齢** (+安静時心拍)。身長・体重はゾーン算出には効かない (消費カロリー計算用)。
- Tanaka 式 HRmax = 208 − 0.7 × 年齢 に当てはめると、39 歳で HRmax ≒ 180.7、155 ≒ 86%、140 ≒ 77%。本人の体感値が %HRmax にきれいに対応するため、初期値は「年齢 → 86% / 77% HRmax」で導出する。
- Health Connect には height / weight / resting HR のレコード型はあるが、年齢・性別のプロファイル型は無い想定 → 生年のみ初回手入力。**実装時に Health Connect の提供型を要確認**。
- 安静時心拍が取れれば将来 Karvonen 式 (心拍予備能) へ切り替え可能。拡張扱い。

### 3. その他の決定 (AskUserQuestion)

- **疲労ブレーキ発動時**: 現サイクルを最終化 (回復完了で終了)。画面操作なしで完結。
- **フェーズタイムアウト**: 上限時間 (高強度 4 分 / 回復 3 分、設定可能) で警告振動 + 強制遷移。セッションを止めない。
- **セッション終了**: 最終サイクルの回復完了 (140 下回り) で**自動停止**。クールダウン表示の継続はしない。

## repo 立ち上げ

- DX は既存個人 repo の構成を踏襲: devbox + direnv / stock-flow ドキュメント / `.claude/rules/` / Stop・PreToolUse hook。
- public repo なので **CI (GitHub Actions) を追加**: gitleaks / docs 配置検査 / gradle build (scaffold 前はスキップ)。hook との検査ロジック共通化のため `scripts/check_docs.sh` を切り出した。
- 言語が Go → Kotlin/Gradle に変わるため、Stop hook から build 系を外した (Gradle はターン内検査には遅い)。build+test は push hook と CI が担う。

## 追記 (同日): 環境分担の決定とアプリ scaffold

### 環境分担

WSL に /dev/kvm があり nested virtualization が有効 (24 コア / 21GB) だったため、**エミュレータ含めすべて WSL 内で完結**と決定。Windows 側 Android Studio は使わない (AI 駆動開発の主経路を CLI に揃える。必要になったら補助として再検討)。

- Android SDK: `scripts/setup_android.sh` (idempotent) で `~/Android/Sdk` へ。nixpkgs の androidsdk は unfree かつ構成が特殊なので devbox 管理にしない
- Wear OS system image は sdkmanager --list から動的に最新を選ぶ方式にした → `system-images;android-34;android-wear;x86_64` (Wear OS 5) が入った
- ハマりどころ: `yes | sdkmanager --licenses` は pipefail 下で SIGPIPE (141) になる → `(yes || true) |` で回避
- 要手作業: ユーザを `kvm` グループに追加 (`sudo usermod -aG kvm <user>`) + WSL 再起動

### scaffold の構成判断

- `:core` (純 Kotlin) + `:app` (Wear OS) の 2 モジュール。ロジックのエミュレータ非依存検証という要件をモジュール境界で強制する
- `IntervalEngine` は時計の抽象すら持たず、呼び出し側が経過時間 (単調増加) を渡す設計にした。fake clock より単純で、テストが (bpm, 経過秒) の表になる
- イベントは 1 遷移 = 1 発火 (振動パターンと 1:1 対応させるため)。タイムアウト遷移は `PhaseTimeout` のみ、疲労遷移は `FatigueBrake` のみを発火し、通常の `EnterRecovery`/`EnterHighIntensity` と重ねない
- 回復タイムアウトでもサイクルは完了扱い (最終サイクルなら強制終了)。セッションが止まらないことを優先する要件の帰結
- バージョン: AGP 8.10.1 / Gradle 8.11.1 / Kotlin 2.1.21 / Wear Compose 1.4.1 / compileSdk 35 / minSdk 30 (Pixel Watch 初代 = Wear OS 3)
- Gradle wrapper は gradle/gradle の v8.11.1 タグから取得 (ローカルに gradle CLI を恒久導入しないため)

## 残課題 (open)
- [ ] Health Connect に年齢・性別プロファイルが本当に無いか実装時に確認
- [ ] ExerciseClient の心拍サンプリング頻度・遅延をエミュレータで確認 (閾値判定の応答性に直結)
- [ ] (開発外) Pixel Watch 購入前の FeliCa (iD/QUICPay) 対応世代確認・手持ちクレカの Google ウォレット対応確認

# 安静時心拍を Health Connect から自動同期する検討経緯

## 動機

ユーザ要望: 「RESTING HR は日々変動するので Health Connect から自動取得したい。phone アプリの HC 連携が ON なら取得したい」。

直前 PR で Karvonen 式の入力に `restingBpm` を追加したが、手入力のままだと現実の変動を反映できず、Karvonen の個別化メリットが薄れる。

## 採用した実装

`HealthIngestWorker.doWork()` の末尾に、取得済みの `DailyHealthRecord.restingHeartRateBpm` の中で最新の非 null 値を取り、`SessionConfig.restingBpm` に反映するステップを追加。

- 反映経路は既存の `PhoneSync.updateSettingsEverywhere` (DataStore → watch Data Layer → Firestore)。watch 側は追加実装なし。
- HC が利用不可 / 権限なし / RHR レコードなし のとき、worker は既存挙動どおり何もしない (RHR は null になるのでスキップ)。
- 値が現在と同じ場合はスキップして LWW タイムスタンプの churn を避ける。
- `SessionConfig` の require レンジ (30..120) を外れる値は反映スキップして警告ログ。

## 悩んだ点と判断

### 別トグルを設けるか

選択肢: (a) HC が grant 済みなら自動同期、(b) 設定画面に「Auto-sync from HC」トグルを追加。

判断: (a)。ユーザの言葉どおり「HC 連携が ON」が暗黙のトグル。明示トグルを増やすと「HC 連携は ON だけど RHR は手入力」みたいな半端な状態が生まれて UX 上の意味が薄い。

### 上限/下限も自動再計算するか

選択肢: (a) RHR だけ更新、(b) RHR 更新時に Karvonen で upper/lower も再計算。

判断: (a)。(b) にすると、ユーザがその日の体感で UPPER/LOWER を手動調整した値が翌朝 HC 取得で上書きされる。ユーザの「最後に編集した意図」を尊重する LWW の原則と合わない。

トレードオフ: 真に「RHR の変動を訓練ゾーンに反映」したいなら、後続で「現プロファイルから再計算する」明示アクションを設定画面に追加する (`docs/stock/requirements.md` の MVP 後拡張に記載済)。

### LWW のタイミング競合

phone UI で手動編集した直後に worker が走ると HC 値で上書きされる可能性がある。worker は 06:00 / 初回 backfill / WorkManager の retry くらいしか走らないので、競合は実害を出すほど頻繁ではないと判断。ログには差分更新のみ記録する。

### watch から HC を直接読みたくならないか

選択肢: watch でも HC client を持って RHR を読む。

判断: しない。Wear OS は HC SDK が貧弱な上、watch にネットワーク権限を増やしたくない (Wearable Data Layer 経由の sync に閉じる原則)。phone を SoT (Source of Truth) に保つ。

## 後続の余地

- 設定画面 (phone / watch) に「現プロファイルから Karvonen で再計算」アクション (`docs/stock/requirements.md` 拡張)。
- RHR トレンドの可視化 (HistoryScreen に近接日の RHR を出す)。
- HRV / 睡眠スコアもセッション設定に影響させるか (要件未定。要研究)。

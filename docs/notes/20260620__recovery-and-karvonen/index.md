# 回復遅延の疲労シグナル化と Karvonen 式導入の検討経緯

## 動機

学術的に裏付けの厚いインターバル評価アプローチを追加したい。

- ユーザ質問: 「今のロジックよりも学説的に裏打ちされたよいアプローチはあるか」。
- 既存ロジックは「同じ運動なのに高強度フェーズが短くなる」 (= 高強度短縮) を疲労シグナルにしている。これは妥当だが片側だけのシグナル。

## 採用した 2 改善

### 1. 回復遅延を疲労シグナルに追加 (両側シグナル化)

学術的裏付け:

- **Cole et al. 1999 NEJM**: 1 分後 HRR (Heart Rate Recovery) < 12bpm は予後不良の独立予測因子。
- **Imai et al. 1994**: HRR は自律神経 (副交感神経再活性) の指標。
- **Buchheit & Laursen 2013 (Sports Med, HIIT review)**: work:recovery 比よりも recovery kinetics が個人の現有適応度を反映。

実装: 既存の高強度短縮判定 (`IntervalEngine.judgeFatigue`) の鏡像として、
回復時間が初回基準の `recoveryFatigueRatio` 倍 (デフォルト 1.5x) を超えたら
疲労扱いでセッション終了。基準は「高強度基準が確定したサイクル」の回復時間
にすることで、筋トレ直後ケース (初回高強度が短すぎて歪んだ計測) の影響を避ける。

### 2. Karvonen 式で閾値初期値を導出

学術的裏付け:

- **Karvonen et al. 1957**: HRR (心拍予備能) ベースの強度処方。%HRmax より個人差
  (特に安静時心拍) を反映できる。

実装: `core/HeartRateZones.kt` に Tanaka 式 (HRmax = 208 − 0.7 × 年齢) と
Karvonen 式 (target = restHR + intensity × (maxHR − restHR)) の純関数を追加。
`SessionConfig` のデフォルト upperBpm/lowerBpm をこれで導出する。
設定 UI に `AGE` / `RESTING HR` を追加し、ユーザが個人プロファイルとして編集可能に。

## 悩んだ点と判断

### 回復ベースラインの妥当性ガード

最初は「初回サイクルの回復時間 = 基準」とシンプルに実装したが、筋トレ直後ケースで
初回高強度が短すぎる (心拍が既に下限超え状態) と、初回回復時間も歪む可能性を懸念。

判断: 高強度側の `minBaseline` ガードと連動させ、「高強度基準が確定したサイクル」の
回復だけを基準にする (`if (recoveryBaseline == null && baseline != null) ...`)。
新たな config 項目は増やさず、既存の minBaseline で間接的にカバー。

### 終了イベントの設計

回復疲労ブレーキの発火タイミングは「サイクル完了 (= recovery → high の遷移点)」。
高強度疲労 (`FatigueBrake` イベントで回復に入る → 後に `SessionFinished`) と違い、
回復疲労は即座に session が終わるため遷移を分けるイベントが無い。

判断: 既存のタイムアウト経路 (`onTimePassed`) と同じく、`SessionFinished` を返しつつ
`fatigueBrakeFired` フラグを立てる。振動は SessionFinished の「長 3」だけになるが、
画面上で `fatigueBrake=true` が見える + 履歴に残るので識別性は確保。

### Karvonen を upperBpm/lowerBpm の SoT (Source of Truth) にするか

検討: 「upperBpm/lowerBpm を持たず、age + restingBpm + intensity から都度計算」案。
個人プロファイルが SoT になり、より宣言的。

判断: 現状の upperBpm/lowerBpm 直接編集の UX (セッション中の ±1bpm ナッジ含む) を
維持したいため、Karvonen は「デフォルト初期値の導出元」にとどめる。upperBpm を
明示指定すればそれが優先される (`SessionConfig` の data class default 機構)。
ユーザは age/restingBpm を編集→将来 PR で「APPLY」ボタンを足せば導出値で
上書きできる、という拡張余地を残す。

### 既存ユーザへの影響

DataStore に保存済みの 155/140 値はそのまま読まれる (新しい defaults は適用されない)。
個人 1 名のプロジェクトなので影響は小さく、好みに応じて再設定可能。
リインストール後の new install では Karvonen 由来の 164/153 がデフォルトになる。

## 後続の余地 (このPRに入れない)

- 設定 UI に「APPLY KARVONEN」ボタン (現在の age/restingBpm から upper/lower を再計算)。
- Health Connect から restingBpm を自動取得 (要件 docs/stock/requirements.md 拡張)。
- 履歴画面に回復時間トレンド (recoveryDurationsSec の可視化)。

## 参考文献メモ

- Tanaka et al. 2001. Age-predicted maximal heart rate revisited. J Am Coll Cardiol.
- Karvonen et al. 1957. The effects of training on heart rate. Ann Med Exp Biol Fenn.
- Cole et al. 1999. Heart-rate recovery immediately after exercise as a predictor of mortality. N Engl J Med.
- Buchheit & Laursen 2013. High-intensity interval training, solutions to the programming puzzle. Sports Med.
- Imai et al. 1994. Vagally mediated heart rate recovery after exercise. J Am Coll Cardiol.

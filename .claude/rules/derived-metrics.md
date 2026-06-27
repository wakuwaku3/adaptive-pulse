---
description: 派生指標 (TDEE / deficit 等) と実測値 (体重 / BMI 等) の欠損ハンドリング方針
paths:
  - "core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/calories/**"
  - "core/src/main/kotlin/io/github/wakuwaku3/adaptivepulse/core/sync/DailyHealthRecord.kt"
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/calories/**"
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/health/**"
  - "mobile/src/main/java/io/github/wakuwaku3/adaptivepulse/mobile/ui/dashboard/**"
---

# 派生指標と実測値の欠損ハンドリング

「**表示する値**」と「**派生計算の入力**」を分けて扱う。実測値が当日欠損しても、それを入力に持つ派生指標まで欠損で落とさない。

- **実測値 (体重 / 体脂肪 / 身長 / 摂取カロリー / 睡眠など)**: 当日に記録がなければ `null` のまま表示する (`—`)。穴を埋めない。これらはトレンドチャートや健康判断の直接根拠なので、推定で埋めるとユーザが誤って読み取る。
- **派生指標 (TDEE / NEAT / deficit など)**: 計算入力に slowly-varying な実測値 (= 日次変動が小さい体重・身長等) が必要なら、**当日欠損時は「最新既知値」をフォールバック入力にして算出する**。当日に体重を測らなかった日でも TDEE が出て、deficit / トレンドが穴あきにならないようにする。
- **派生指標のフォールバックは入力にのみ適用し、`DailyHealthRecord` の実測フィールドには波及させない**。例: `record.weightKg = null` の日でも `TdeeCalc` には `fallbackWeightKg` を渡して TDEE を計算するが、`DailyHealthRecord.weightKg` 自体は `null` のまま保存・表示する。これにより:
  - 体組成チャート / Today カードの Weight / BMI: 未測定日は欠損 (現実を歪めない)
  - TDEE / deficit: 毎日出る (体験ロスを避ける)
- 「最新既知値」は HC の `readLatestEver` 相当 (= 過去の任意時点で最後に記録された値) を取る。期間 cap は付けない: 何ヶ月も測ってない異常運用はそもそも想定外で、cap せずとも誤差は小さい (体重 1kg ズレても運動 extra への影響は数 % 以内)。
- 一度も記録が無いユーザ (新規) は派生指標も `null` で正しい。これはオンボーディング前の状態。

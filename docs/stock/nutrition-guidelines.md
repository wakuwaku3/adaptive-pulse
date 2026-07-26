# 栄養評価の基準値と出典

phone ダッシュボードの帯 (`mobile/ui/dashboard/DashboardModels.kt`) と `/analyze` の下限判定 (`scripts/analyze.sh`) が共有する栄養基準。`design-basis` ルールに従い、値はすべて文献に根拠を置く。**2 実装の境界値は常に一致させる** (グラフの「不足」境界 = /analyze の下限)。

## タンパク質 — 目標体重 × 1.6–2.2 g/kg/日 (下限 1.6)

- 筋トレ併用時の筋量・筋力向上に効くタンパク質摂取のブレークポイントは約 1.6 g/kg/日 (Morton et al. 2018, Br J Sports Med のメタ分析)。
- カロリー赤字下のトレーニング者は同等以上が必要 (Helms et al. 2014, IJSNEM レビュー)。減量目的の食事管理では 1.2–1.6 g/kg が推奨帯 (Leidy et al. 2015, AJCN)。
- **アンカーは現体重ではなく目標体重**: 体脂肪が多い場合、現体重あたりの g/kg は必要量を過大評価する。筋量維持に必要な量は除脂肪量に比例するため、目標体重 (減量ゴール) あたりで適用する。目標体重は設定 (`SessionConfig.targetWeightKg`) から取り、未設定時は身長から BMI 22 の標準体重 (日本肥満学会) で代用する。

## 脂質 — 現体重 × 0.5–1.5 g/kg/日 (下限 0.5)

- 減量中アスリートの推奨脂質は 0.5–1.5 g/kg/日で、0.5 g/kg を下回るとホルモン機能 (テストステロン等) 低下のリスク (Helms et al. 2014)。
- 摂取カロリー約 2,000 kcal/日 のとき 0.5 g/kg × 90 kg ≒ 45 g ≒ 20% となり、AMDR (脂質 20–35%; IOM 2005) の下端とも整合する。

## 炭水化物 — 下限なし (現体重 × 2–4 g/kg/日 を「ふつう」帯として表示のみ)

- タンパク質・脂質の下限を満たした残りをエネルギー収支で調整する変数として扱い、下限判定はしない。2–4 g/kg は中程度の運動量での一般的推奨 (Burke et al. 2011, J Sports Sci)。低糖質はユーザが意図的に選びうるため問題化しない。

## 実装箇所

| 消費側 | 役割 |
|---|---|
| `mobile/ui/dashboard/DashboardModels.kt` (`Protein` / `Fat` / `Carbs`) | チャート帯と Today カードの色判定 |
| `scripts/analyze.sh` (`$pFloor` / `$fFloor`) | /analyze の下限割れ日の列挙 |
| `SessionConfig.targetWeightKg` | 目標体重 (phone Settings で編集、LWW 同期) |

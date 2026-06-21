# TDEE を自前計算に切り替える

## 動機

Pixel Watch (Fitbit / Connected Fitness 経由) が HC に書く `TotalCaloriesBurnedRecord`
が実測比で過大評価される (例: 06-19 で watch total 4832 kcal、Mifflin-St Jeor BMR
1818 の 2.66x = 17666 歩 + HIIT + 1h ハード有酸素では非現実的)。これを `TotalCaloriesBurnedRecord.ENERGY_TOTAL`
aggregate で受けて TDEE として表示してしまっており、deficit / トレンド分析の根拠
にならない。

`docs/notes/20260620__dashboard-redesign/` で「breakdown を可視化する」対応は入れたが、
TDEE 算出自体の差し替えは未実装だった。

## ゴール

- watch / Fitbit の inflated total を **TDEE 計算に混ぜない**
- 歩数だけだと拾えない運動 (屋内バイク / クロストレーナー / ローイングマシン /
  ウェイトトレーニング) を **MET 換算で加算する**
- 自社 HIIT セッションと watch が書いた HC ExerciseSession の **二重計上を回避**する
- 算出ロジックは Android 非依存の純 Kotlin に置き、JVM 単体テストだけで検証する

## 採用する式

```
TDEE = BMR + NEAT + ExerciseExtra

BMR          = basalCaloriesKcal ?: MifflinStJeor(weight, height, age)
NEAT         = steps × (weight × 0.0005)     // kcal/step は体重連動
ExerciseExtra = Σ (MET - 1) × weight × hours
  対象 session:
    - 自社 HIIT (`SessionRecord`): MET = 8.0 (高強度 + 回復ミックスの平均)
    - HC `ExerciseSessionRecord`: type 別 MET 表を引く
      - WALKING / RUNNING は歩数で吸収済なので extra に加算しない (stepCovered=true)
      - 自社 HIIT と時間範囲が重複する HC session はスキップ (二重計上回避)
```

MET 表:

| type | MET | step covered? |
|---|---|---|
| WALKING | 3.5 | yes (歩数で拾う) |
| RUNNING | 9.0 | yes (歩数で拾う) |
| BIKING (屋外) | 7.5 | no |
| BIKING_STATIONARY | 7.0 | no |
| ELLIPTICAL | 7.0 | no |
| ROWING / ROWING_MACHINE | 7.0 | no |
| STRENGTH_TRAINING / WEIGHTLIFTING | 5.0 / 3.5 | no |
| HIGH_INTENSITY_INTERVAL_TRAINING | 8.0 | no |
| (上記以外) | 5.0 | no |

## 「他アプリの書き込みを禁止」について

HC API には他アプリの書き込みを禁止する primitive が無い。等価な運用:

1. **読む側**: 自社が信頼する dataOrigin だけで TDEE を組む (= 我々の HIIT
   session + HC のメタデータとしての ExerciseSessionRecord の型情報のみ。watch が
   書いた `TotalCaloriesBurnedRecord` の値は **TDEE 計算に混ぜない**)
2. **書く側**: 自社 HIIT セッション終了時に HC へ `ExerciseSessionRecord` +
   `ActiveCaloriesBurnedRecord` を書き戻す (Phase 2)。これで MyFitnessPal / Google
   Fit 等が HC を読んだとき、こちらの値も見える
3. **手動 revoke**: ユーザが HC 設定で Fitbit の calorie 書き込み権限を切れば
   完全に断てる (アプリ側からは制御不可)

## 段取り

- **Phase 1 (本コミット)**: `core/calories/{MetTable, TdeeCalc}` 追加 + テスト +
  `DailyHealthRecord` / `DailySnapshotEntity` に `tdeeKcal` / `exerciseExtraKcal`
  追加 + `HealthDataSource.readDay` が `TdeeCalc.compute` を呼ぶ + dashboard が
  新フィールドを使う + Room version bump (destructive migration で既存キャッシュ破棄、
  HC から再同期)。Firestore には `tdeeKcal` / `exerciseExtraKcal` が以降乗る
- **Phase 2 (続け)**: HC への書き戻し (`WRITE_EXERCISE` / `WRITE_ACTIVE_CALORIES_BURNED`
  権限追加 + HIIT session 終了時の writer)
- **Phase 3**: `docs/stock/health-data-export.md` を新方式に更新

## 悩んだ点と判断

### 二重計上回避を type で見るか時間で見るか

HC が「ELLIPTICAL session」を書いていて、それが自社 HIIT と重なる場合と、独立に
1h クロストレーナーをやった場合がある。type ベース判定だと前者を吸収できない。

→ **時間範囲の重複**で判定。`appSession.startedAtMs..appSession.endMs` と
`hcSession.startTimeMs..endTimeMs` が overlap したら HC 側を skip する。自社の
SessionRecord はあるが、watch が同じ運動を別ソースとして HC にも書いていれば
overlap で検出できる。

### 歩数で拾える type をどこまで広げるか

ELLIPTICAL や ROWING_MACHINE は歩数を動かさないので加算が必要。WALKING / RUNNING
は外でやれば歩数に乗る (屋内ランは treadmill だが、これは少ないので一旦 RUNNING
は step covered 扱いで OK — 必要になれば revisit)。

### MET 値の精度

文献値の中央を使う (`Compendium of Physical Activities 2024`)。個人差はあるが
±15% 程度で、watch の 2-3x オーバーよりは桁違いに信頼できる。

### 既存 Firestore データの扱い

Phase 1 以前の `dailyMetrics` ドキュメントには `tdeeKcal` / `exerciseExtraKcal`
フィールドが無い。`@Serializable` のデフォルト null で読めるので破壊的でない。
過去日を **再計算する** には `HealthSyncWorker` を 7 日分回せばよい (HC が原本)。

過去より前の日 (例えば 1 年前のトレンド) は HC に履歴があれば再同期で再計算
できる。30 日より古い日は `READ_HEALTH_DATA_HISTORY` が要る。

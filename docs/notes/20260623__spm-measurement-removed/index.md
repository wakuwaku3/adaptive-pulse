# 実測 SPM の廃止と目標 SPM だけの構成への単純化

2026-06-23

## 経緯

- pace-metric note (`docs/notes/20260620__pace-metric/`) で導入した 3 段フォールバック実測 SPM (`STEPS_PER_MINUTE` / `STEPS_TOTAL` 差分 / 加速度 peak) と、それを anchor にした制御ループ / intra-cycle stall 検知 / セッション間 rolling median 持ち越しが、実機でいずれも安定しなかった。
- 実測値は機材 (クロストレーナー) と腕振りの周期に大きく依存し、tier 1 と tier 3 で 2 倍前後の値が出ることもあって「target をどこに置きたいか」のシグナルとして使えなかった。
- 「実測SPM は全くあてにならないので機能として削除してください。SPM は単純に hard と recover の時の目標値だけ設定可能にして、回転体はそれによって速度を変更してください」(2026-06-23 ユーザ FB) で機能を全削除。

## 廃止したもの

- `:core/cadence/` 一式 (`TieredCadenceLock`, `AccelerometerCadenceEstimator`, `StepsDeltaCadenceEstimator`, `RollingMedian`, `CadenceTier`) と単体テスト
- `:app` の `AccelerometerCadenceSource` / `HealthServicesExerciseSource` の `STEPS_PER_MINUTE` / `STEPS_TOTAL` 経路 / `ExerciseSample.stepsPerMinute` / `cadenceTier`
- `:core/IntervalEngine` の `onCadenceSample`、制御ループ (`updateTargetCadenceHigh/Recovery`, `applyControlLoop`)、intra-cycle stall 検知 (`maybeNudgeForStall`)、target cadence の `var` 化と動的 ± nudge (`adjustActiveTargetCadence`)
- `SessionConfig` の cadence 制御パラメタ (`cadenceTargetHighDurationMin/Max`, `cadenceTargetRecoveryDurationMin/Max`, `cadenceControlGain`, `cadenceStall*`)
- `SessionRecord` の `lockedCadenceTier` / `finalTargetCadenceHigh/Recovery` (= 次セッション持ち越し)
- `SessionLiveSnapshot.currentCadenceSpm` (実測経路)
- phone の `LiveSessionCommander.adjustTargetCadence` / `MessageClient` パス `SESSION_CMD_TARGET_SPM` / `ActiveSessionScreen` の SPM ± ボタンと NowValues 行
- `SessionPhaseSnapshot` (TieredCadenceLock discovery 用に warmup 状態を渡していた DTO)

## 残したもの

- 「目標 cadence」はユーザが Settings で設定する単純な `Int` 2 つだけに集約: `SessionConfig.targetCadenceHigh` (デフォルト 130) と `targetCadenceRecovery` (デフォルト 90)
- phone ライブ画面の `PaceEllipse` (2 ドット楕円周回アニメ) は active phase の目標 SPM (設定値) で回す。実測値とのズレ色判定 (within / 速すぎ / 遅すぎ) は廃止し、phase 色そのままで描く
- `SessionLiveSnapshot.targetCadenceHigh/Recovery` (`Int`) は phone 回転体の tempo として残す
- `SettingItem` に `TARGET SPM (HIGH)` / `TARGET SPM (RECOVERY)` の 2 行を追加

## schema bump

- `SessionRecord.schema` を 2 → 3 に bump (`lockedCadenceTier` / `finalTargetCadenceHigh/Recovery` の削除)
- `SessionLiveSnapshot.schema` を 4 → 5 に bump (`currentCadenceSpm` の削除、`targetCadenceHigh/Recovery` の `Double` → `Int` 化)
- `SyncPaths.SESSION_CMD_TARGET_SPM` を削除
- 旧クライアントからの JSON は `ignoreUnknownKeys = true` で問題なく読める (test に legacy 互換テストを残した)

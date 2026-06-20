# Phone ライブセッション画面 + 自動起動

## 背景

ユーザの実運用上の課題:

> 運動中の課題として、watch を見れないので心拍数とかの現在パラメータがわからない。セッション中は phone アプリの方の画面も遷移してステータス表示するのは動かないか。またセッション強制的に phone アプリ起動や、セッション中はスリープにしないとかの対応も必要。

毎朝ジムで実運用中の課題なので「拡張 (MVP 後)」ではなく運用品質要件として扱う ([[feedback-no-mvp-framing]])。

## 統合スコープ

別 flow note (`docs/notes/20260620__pace-metric/index.md`) が「watch → phone へのライブ状態同期と統合する」前提で書かれていたため、本セッションで一緒に実装する:

- ライブ状態同期 (本機能の本丸): 心拍・フェーズ・サイクル・3 経過時間・閾値・カロリー
- ペース計測 (pace-metric Phase A): `currentRps` を同じ DTO に乗せて運ぶ。`targetRps` や拍動円 UI は Phase B 以降 (Phase A の実測結果待ち)

pace-metric Phase A の「実測結果が出るまで stock 昇格しない」方針 (note line 106) は維持。requirements.md には phone 表示と自動起動だけを書き、cadence は触れていない。

## 同期スキーマ

`/session/live` を `DataClient` で扱う (最新スナップショットの上書き保存。phone が後から起動しても直近の状態に追従できる)。

```kotlin
@Serializable
data class SessionLiveSnapshot(
    val schema: Int = 1,
    val updatedAtMs: Long,
    val phase: String,                 // "WARM_UP" | "HIGH" | "RECOVERY" | "DONE"
    val bpm: Int?,
    val currentCycle: Int,
    val finalCycle: Int,
    val elapsedSec: Double,
    val cycleElapsedSec: Double,
    val phaseElapsedSec: Double,
    val upperBpm: Int,
    val lowerBpm: Int,
    val calories: Double? = null,
    val currentRps: Double? = null,    // 3〜5 秒窓の median (pace-metric Phase A)
)
```

`SessionUiState` (watch UI 用 sealed interface) と分けて DTO にした理由: watch UI の都合で `SessionUiState` は将来も変わりうる。転送境界に DTO を挟むことで結合度を下げる。

## 自動起動

`MessageClient` で `/session/start-foreground` を session 開始時に発射 (再送・永続不要)。phone 側で受けて full-screen intent 通知を出し、`MainActivity` を起動 → live snapshot の有無でルートを分岐させ Active 画面へ。

permission:
- `POST_NOTIFICATIONS` (Android 13+, 通常許可)
- `USE_FULL_SCREEN_INTENT` (Android 14+, ユーザ設定で許可)

初回未許可でも通知 (高優先度) は出るので、ユーザがタップすれば開く。

## Cadence サンプリング

`HealthServicesExerciseSource` で `DataType.STEPS_PER_MINUTE` を能力確認のうえ subscribe。`ExerciseSample.stepsPerMinute` に乗せて伝搬。

クロストレーナーで意味ある値を返すかは実機検証が必要 (pace-metric note line 102)。返さなければ次セッションで加速度自前検出 (FFT or peak detection) に差し替える。本セッションでは interface 上に Health Services 経路だけ用意し、出ない場合は `currentRps=null` で送信。

`:core` の `RollingMedian` (純 Kotlin) で 5 秒窓の median を出す。JVM 単体テストで合成サンプルを流して検証。

## 段階分けの判断

ユーザ指示「段階的に進めず、全部やりきってください」のため、要件追記から実装・PR・マージまでを 1 PR で実施。

## 残課題 (実機検証後)

- クロストレーナーで `STEPS_PER_MINUTE` が来るか
- full-screen intent の Android 14+ ユーザ許可フローが想定通りか
- `DataClient` の live snapshot 反映遅延が実用上問題ないか (目標: <2 秒)
- pace-metric Phase B (拍動円 + `targetRps`) 着手判断

## 関連

- 要件: `docs/stock/requirements.md` §Phone ライブセッション画面
- pace-metric: `docs/notes/20260620__pace-metric/index.md`
- 同期スキーマ既存: `docs/stock/sync.md` (`SessionRecord` 用、live 系は本 PR で `:core/sync/` に追加)

package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlinx.serialization.Serializable

/**
 * ジム 1 回分の筋トレ記録 (ワークアウト)。phone が生成し、セット入力のたびに
 * ローカル保存 + Firestore `workouts/{id}` へ upsert する (進行中も送る)。
 * 有酸素の session (watch 起点) と対になる phone 起点のレコード。
 */
@Serializable
data class WorkoutRecord(
    val id: String,
    val schema: Int = 1,
    val gymId: String,
    /** rename に影響されず記録単体で読めるよう名前をスナップショットで持つ */
    val gymName: String,
    /** 最初の入力時刻。画面を開いただけでは workout は始まらない */
    val startedAtMs: Long,
    val lastInputAtMs: Long,
    /** null = 進行中 */
    val endedAtMs: Long? = null,
    /** [WorkoutEndReason]。null = 進行中 */
    val endReason: String? = null,
    val entries: List<WorkoutEntry> = emptyList(),
    val device: String = "phone",
)

/** workout 内の 1 トレーニング分の実績 */
@Serializable
data class WorkoutEntry(
    val trainingId: String,
    val trainingName: String,
    /** 「今日はやらない」の明示マーク。セット記録とは実運用上排他 */
    val skipped: Boolean = false,
    val sets: List<TrainingSet> = emptyList(),
)

@Serializable
data class TrainingSet(
    /** null = 自重・ストレッチ等の負荷なし */
    val weightKg: Double? = null,
    val reps: Int,
    /** セット間の休憩間隔の分析と lastInputAtMs の根拠 */
    val recordedAtMs: Long,
)

/** enum でなく文字列定数なのは、JSON 上の表現を将来の追加に対して寛容に保つため */
object WorkoutEndReason {
    /** 明示的な Finish ボタン */
    const val FINISH = "finish"

    /** 有酸素セッション開始による自動終了 */
    const val CARDIO = "cardio"

    /** 最終入力から一定時間経過による自動終了 */
    const val TIMEOUT = "timeout"
}

package io.github.wakuwaku3.adaptivepulse.core

import io.github.wakuwaku3.adaptivepulse.core.menu.MenuKind
import io.github.wakuwaku3.adaptivepulse.core.menu.PlannedSegment
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlan
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 1 セグメント (= プラン内の 1 メニュー実行分) の実績。履歴 SessionRecord の材料 */
data class SegmentResult(
    val segment: PlannedSegment,
    /** 心拍トリガー型の完了本数。時間制は null */
    val completedCycles: Int?,
    val elapsed: Duration,
    val highDurations: List<Duration>,
    val recoveryDurations: List<Duration>,
)

/**
 * プラン (メニューの列) を順に消化するオーケストレーション
 * (docs/stock/requirements.md「プログラム」)。セグメントごとに
 * [IntervalEngine] / [TimedEngine] を生成し、継ぎ目で [SessionEvent.EnterNextMenu] を発火する。
 *
 * elapsed はセッション開始からの経過時間 (単調増加)。内部エンジンには
 * セグメント開始からの相対時間に変換して渡す (タイムアウト・時間制の分数が
 * セグメント単位で意味を持つため)。
 */
class PlanEngine(
    val plan: SessionPlan,
    private val baseConfig: SessionConfig,
) {
    var segmentIndex: Int = 0
        private set

    private var segmentStartedAt: Duration = Duration.ZERO

    var intervalEngine: IntervalEngine? = null
        private set

    var timedEngine: TimedEngine? = null
        private set

    var finished: Boolean = false
        private set

    /** タイムアウトのセーフティ停止が起きたか。プログラム全体を終える (次メニューへ進まない) */
    var fatigueBrakeFired: Boolean = false
        private set

    private val completedResults = mutableListOf<SegmentResult>()

    init {
        startSegment(plan.segments.first())
    }

    val currentSegment: PlannedSegment get() = plan.segments[segmentIndex]

    val segmentCount: Int get() = plan.segments.size

    fun onHeartRate(bpm: Int, elapsed: Duration): SessionEvent? {
        if (finished) return null
        val inner = elapsed - segmentStartedAt
        val event = intervalEngine?.onHeartRate(bpm, inner) ?: timedEngine?.onHeartRate(bpm, inner)
        return advanceIfSegmentFinished(event, elapsed)
    }

    /** 心拍サンプルが途絶えてもタイムアウト・時間経過の遷移が動くよう、定期 tick からも呼べる */
    fun onTimePassed(elapsed: Duration): SessionEvent? {
        if (finished) return null
        val inner = elapsed - segmentStartedAt
        val event = intervalEngine?.onTimePassed(inner) ?: timedEngine?.onTimePassed(inner)
        return advanceIfSegmentFinished(event, elapsed)
    }

    /** 閾値ナッジは心拍トリガー型セグメント中のみ有効。時間制中は何もしない */
    fun adjustActiveThreshold(delta: Int): Int =
        intervalEngine?.adjustActiveThreshold(delta) ?: activeUpperBpm

    /** 現セグメントが見ている帯の上限 (UI 表示・zone 計測) */
    val activeUpperBpm: Int
        get() = intervalEngine?.upperBpm ?: requireNotNull(timedEngine).upperBpm

    /** 現セグメントが見ている帯の下限。時間制で下限なしのとき null */
    val activeLowerBpm: Int?
        get() = intervalEngine?.lowerBpm ?: requireNotNull(timedEngine).lowerBpm

    val isWarmingUp: Boolean get() = intervalEngine?.isWarmingUp ?: false

    /** ウォームアップ除外は心拍トリガー型のみ。時間制は全サンプルを計測に入れる */
    val inMeasurement: Boolean get() = intervalEngine?.let { !it.isWarmingUp } ?: true

    val latestSuggestion: SessionSuggestion? get() = intervalEngine?.latestSuggestion

    val activeCadenceHigh: Int
        get() = when (val kind = currentSegment.menu.kind) {
            is MenuKind.Interval -> kind.targetCadenceHigh
            is MenuKind.Timed -> kind.targetCadence
        }

    val activeCadenceRecovery: Int
        get() = when (val kind = currentSegment.menu.kind) {
            is MenuKind.Interval -> kind.targetCadenceRecovery
            is MenuKind.Timed -> kind.targetCadence
        }

    /** 心拍ソースに見せるフェーズ。時間制は合成ソースが動き続けるよう高強度扱いにする */
    val sourcePhase: Phase
        get() = when {
            finished -> Phase.FINISHED
            else -> intervalEngine?.phase ?: Phase.HIGH_INTENSITY
        }

    fun segmentElapsed(elapsed: Duration): Duration = elapsed - segmentStartedAt

    /** 現サイクル経過 (心拍トリガー型)。時間制はセグメント経過そのもの */
    fun cycleElapsed(elapsed: Duration): Duration {
        val inner = elapsed - segmentStartedAt
        val interval = intervalEngine ?: return inner
        return interval.cycleStartedAt?.let { inner - it } ?: Duration.ZERO
    }

    /** 現フェーズ経過 (心拍トリガー型)。時間制はセグメント経過そのもの */
    fun phaseElapsed(elapsed: Duration): Duration {
        val inner = elapsed - segmentStartedAt
        val interval = intervalEngine ?: return inner
        return inner - interval.phaseStartedAt
    }

    /** 完了した本数の合計 (心拍トリガー型セグメントのみ) */
    val cycles: Int
        get() = completedResults.sumOf { it.completedCycles ?: 0 } +
            (intervalEngine.takeIf { !finished }?.currentCycle ?: 0)

    /** 予定していた本数の合計 (心拍トリガー型セグメントのみ) */
    val plannedCycles: Int
        get() = plan.segments.sumOf { if (it.menu.kind is MenuKind.Interval) it.amount else 0 }

    val highDurations: List<Duration>
        get() = completedResults.flatMap { it.highDurations } +
            (intervalEngine.takeIf { !finished }?.highDurations ?: emptyList())

    val recoveryDurations: List<Duration>
        get() = completedResults.flatMap { it.recoveryDurations } +
            (intervalEngine.takeIf { !finished }?.recoveryDurations ?: emptyList())

    /** セグメントごとの実績。途中終了時は進行中セグメントの部分実績も含める */
    fun segmentResults(elapsed: Duration): List<SegmentResult> =
        if (finished) completedResults.toList() else completedResults + snapshotCurrent(elapsed)

    private fun advanceIfSegmentFinished(event: SessionEvent?, elapsed: Duration): SessionEvent? {
        if (event != SessionEvent.SessionFinished) return event
        val brake = intervalEngine?.fatigueBrakeFired == true
        completedResults += snapshotCurrent(elapsed)
        if (brake) {
            fatigueBrakeFired = true
            finished = true
            return SessionEvent.SessionFinished
        }
        if (segmentIndex == plan.segments.lastIndex) {
            finished = true
            return SessionEvent.SessionFinished
        }
        segmentIndex++
        segmentStartedAt = elapsed
        startSegment(plan.segments[segmentIndex])
        return SessionEvent.EnterNextMenu
    }

    private fun snapshotCurrent(elapsed: Duration): SegmentResult {
        val interval = intervalEngine
        return SegmentResult(
            segment = currentSegment,
            completedCycles = interval?.currentCycle,
            elapsed = elapsed - segmentStartedAt,
            highDurations = interval?.highDurations ?: emptyList(),
            recoveryDurations = interval?.recoveryDurations ?: emptyList(),
        )
    }

    private fun startSegment(segment: PlannedSegment) {
        when (val kind = segment.menu.kind) {
            is MenuKind.Interval -> {
                intervalEngine = IntervalEngine(
                    baseConfig.copy(
                        upperBpm = kind.upperBpm,
                        lowerBpm = kind.lowerBpm,
                        targetCycles = segment.amount,
                        targetCadenceHigh = kind.targetCadenceHigh,
                        targetCadenceRecovery = kind.targetCadenceRecovery,
                        minBaseline = kind.minBaselineSecs.seconds,
                        highPhaseTimeout = kind.highTimeoutSecs.seconds,
                        recoveryTimeout = kind.recoveryTimeoutSecs.seconds,
                    ),
                )
                timedEngine = null
            }
            is MenuKind.Timed -> {
                timedEngine = TimedEngine(kind, segment.amount)
                intervalEngine = null
            }
        }
    }
}

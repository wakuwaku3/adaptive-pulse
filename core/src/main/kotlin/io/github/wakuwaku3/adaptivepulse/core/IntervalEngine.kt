package io.github.wakuwaku3.adaptivepulse.core

import kotlin.time.Duration

enum class Phase { HIGH_INTENSITY, RECOVERY, FINISHED }

/**
 * 心拍トリガーのインターバル・ステートマシン (docs/stock/requirements.md)。
 *
 * Android 非依存。時刻は呼び出し側がセッション開始からの経過時間 (単調増加) として
 * 渡すため、時計の抽象化すら持たない。心拍ソース (Health Services / BLE / 合成データ)
 * の差も呼び出し側で吸収する。
 */
class IntervalEngine(
    private val config: SessionConfig,
    /**
     * 高強度目標 cadence の初期値。前回セッション最終値 (持ち越し) or seed。
     * pace-metric note §ペースをどう決めるか / Day-1 seed
     */
    initialTargetCadenceHigh: Double = config.seedTargetCadenceHigh,
    /** 回復目標 cadence の初期値。同上 */
    initialTargetCadenceRecovery: Double = config.seedTargetCadenceRecovery,
) {

    var phase: Phase = Phase.HIGH_INTENSITY
        private set

    /** 現在有効な上限閾値。セッション中に [adjustActiveThreshold] で動かせるが、永続化はされない */
    var upperBpm: Int = config.upperBpm
        private set

    /** 現在有効な下限閾値 */
    var lowerBpm: Int = config.lowerBpm
        private set

    /**
     * 高強度フェーズの目標 cadence (SPM)。
     * cycle 完了毎に target duration 窓 (45〜90s) を達成するよう制御ループで自動調整。
     * 手動 ± も [adjustActiveTargetCadence] で許容するが、次 cycle の自動更新が上書きしうる。
     */
    var targetCadenceHigh: Double = initialTargetCadenceHigh
        private set

    /** 回復フェーズの目標 cadence (SPM)。同様に cycle 毎に duration 窓 (30〜75s) を狙って自動調整 */
    var targetCadenceRecovery: Double = initialTargetCadenceRecovery
        private set

    /**
     * 完了した高強度フェーズの数 = 上限到達によって回復に切り替わった回数。
     * 0 で開始し「上限到達 → 回復へ」のタイミングでインクリメントする。
     * 完了した「サイクル」のカウントとして UI と履歴に出す。
     */
    var currentCycle: Int = 0
        private set

    /** 疲労ブレーキで短縮されうる、このセッションの最終サイクル番号 */
    var finalCycle: Int = config.targetCycles
        private set

    /** 疲労判定の基準となる高強度所要時間。未確定の間は null */
    var baseline: Duration? = null
        private set

    /**
     * 疲労判定の基準となる回復所要時間。回復が遅くなる = 副交感神経再活性が鈍る
     * (Cole et al. 1999 NEJM の Heart Rate Recovery の指標) ので、初回サイクルの
     * 回復時間を基準にする。未確定の間は null。
     */
    var recoveryBaseline: Duration? = null
        private set

    /** 実測できたサイクルごとの高強度所要時間 (体力トレンドの源泉として履歴に残す) */
    val highDurations: List<Duration> get() = measuredHighDurations
    private val measuredHighDurations = mutableListOf<Duration>()

    /** 実測できたサイクルごとの回復所要時間 (体力トレンド・回復遅延検知の源泉) */
    val recoveryDurations: List<Duration> get() = measuredRecoveryDurations
    private val measuredRecoveryDurations = mutableListOf<Duration>()

    /** 疲労ブレーキが発動したか (タイムアウトによる強制終了も含める。履歴用) */
    var fatigueBrakeFired: Boolean = false
        private set

    /** 現フェーズが始まった時刻 (セッション開始からの経過)。UI のフェーズ経過時間に使う */
    var phaseStartedAt: Duration = Duration.ZERO
        private set

    /** 現サイクルの高強度フェーズが始まった時刻 (= サイクル開始時刻)。UI のサイクル経過時間に使う */
    var cycleStartedAt: Duration = Duration.ZERO
        private set

    // 高強度所要時間は「下限閾値を上向きに超えてから上限到達まで」で測る。
    // セッション開始直後のウォームアップ区間を計測から外し、全サイクルを公平に比較するため。
    private var measureStartedAt: Duration? = null

    // pace-metric Phase B: 制御ループは「実測 cadence の phase median」を anchor にする
    // (target 基準だとユーザが target を無視して別ペースで踏んだ場合 target が現実から乖離して固定する)
    private val highCadenceSamples = mutableListOf<Double>()
    private val recoveryCadenceSamples = mutableListOf<Double>()

    /** 直近の実測 cadence (SPM)。intra-cycle stall 検知で「target に近いか」判定に使う */
    private var lastCadenceSample: Double? = null

    // intra-cycle stall 検知: 「実測 cadence は target に達しているのに BPM が動かない」状態を
    // 周期的に検出して、phase 中であろうと target を nudge する (pace-metric 2026-06-21 FB)
    private var stallCheckpointBpm: Int? = null
    private var stallCheckpointAt: Duration = Duration.ZERO

    /**
     * 任意の cadence サンプル (SPM) を engine に流す (SessionRunner / DemoSessionController から呼ぶ)。
     * 現フェーズに蓄積し、cycle 完了時に median を計算して制御ループの anchor として使う。
     */
    fun onCadenceSample(spm: Double) {
        when (phase) {
            Phase.HIGH_INTENSITY -> highCadenceSamples += spm
            Phase.RECOVERY -> recoveryCadenceSamples += spm
            Phase.FINISHED -> Unit
        }
        if (phase != Phase.FINISHED) lastCadenceSample = spm
    }

    /** セッション開始直後、まだ下限閾値を上向きに超えていないウォームアップ区間か */
    val isWarmingUp: Boolean
        get() = phase == Phase.HIGH_INTENSITY && currentCycle == 0 && measureStartedAt == null

    /** 心拍サンプルを処理する。遷移が起きたときだけイベントを 1 つ返す */
    fun onHeartRate(bpm: Int, elapsed: Duration): SessionEvent? {
        onTimePassed(elapsed)?.let { return it }
        val event = when (phase) {
            Phase.HIGH_INTENSITY -> onHighIntensitySample(bpm, elapsed)
            Phase.RECOVERY -> onRecoverySample(bpm, elapsed)
            Phase.FINISHED -> null
        }
        // 遷移が起きていなければ stall 検知を回す。遷移済なら次の phase で初回 sample から始める
        if (event == null) maybeNudgeForStall(bpm, elapsed)
        return event
    }

    /**
     * 心拍サンプルが途絶えてもタイムアウト遷移が動くよう、定期 tick からも呼べる。
     * 閾値に到達しないままフェーズ上限時間を超えた場合は「想定通り運動できていない」
     * 疲労ブレーキ扱いでセッション自体を終了する (次サイクルには進めない)。
     */
    fun onTimePassed(elapsed: Duration): SessionEvent? {
        val limit = when (phase) {
            Phase.HIGH_INTENSITY -> config.highPhaseTimeout
            Phase.RECOVERY -> config.recoveryTimeout
            Phase.FINISHED -> return null
        }
        if (elapsed - phaseStartedAt <= limit) return null

        fatigueBrakeFired = true
        finalCycle = currentCycle
        phase = Phase.FINISHED
        resetStallCheckpoint()
        return SessionEvent.SessionFinished
    }

    private fun onHighIntensitySample(bpm: Int, elapsed: Duration): SessionEvent? {
        if (measureStartedAt == null && bpm > lowerBpm) {
            measureStartedAt = elapsed
            // warmup 終了 = "real HIGH" 開始。stall 用の checkpoint をここからリセットして
            // grace period を真の高強度フェーズ突入から数える
            resetStallCheckpoint()
        }
        if (bpm <= upperBpm) return null

        // 上限到達 = この高強度フェーズ完了 → cycle カウントをここで進める
        currentCycle++
        val highDuration = elapsed - (measureStartedAt ?: elapsed)
        // pace-metric Phase B: cycle 完了時に制御ループで高強度 target を更新
        updateTargetCadenceHigh(highDuration)
        val event = judgeFatigue(highDuration)
        enterRecovery(elapsed)
        return event
    }

    private fun judgeFatigue(highDuration: Duration): SessionEvent {
        measuredHighDurations += highDuration
        val base = baseline
        if (base == null) {
            if (highDuration >= config.minBaseline || currentCycle > 1) {
                baseline = highDuration
            }
            return SessionEvent.EnterRecovery
        }
        if (highDuration <= base * config.fatigueRatio && currentCycle < finalCycle) {
            finalCycle = currentCycle
            fatigueBrakeFired = true
            return SessionEvent.FatigueBrake
        }
        return SessionEvent.EnterRecovery
    }

    private fun onRecoverySample(bpm: Int, elapsed: Duration): SessionEvent? {
        if (bpm >= lowerBpm) return null
        return completeCycle(elapsed)
    }

    /**
     * 現フェーズで「次の遷移を起こす閾値」を delta だけ動かす。
     * 高強度フェーズ (WARM-UP 含む) では上限、回復フェーズでは下限を対象にする。
     */
    fun adjustActiveThreshold(delta: Int): Int {
        when (phase) {
            Phase.HIGH_INTENSITY -> {
                upperBpm = (upperBpm + delta).coerceIn(lowerBpm + MIN_THRESHOLD_GAP, MAX_BPM)
                return upperBpm
            }
            Phase.RECOVERY -> {
                lowerBpm = (lowerBpm + delta).coerceIn(MIN_BPM, upperBpm - MIN_THRESHOLD_GAP)
                return lowerBpm
            }
            Phase.FINISHED -> return upperBpm
        }
    }

    /** 現フェーズが監視している閾値 (UI のフィードバック表示用) */
    fun activeThreshold(): Int = when (phase) {
        Phase.HIGH_INTENSITY -> upperBpm
        Phase.RECOVERY -> lowerBpm
        Phase.FINISHED -> upperBpm
    }

    /** 現フェーズの目標 cadence (UI のフィードバック表示・拍動円 tempo に使う) */
    fun activeTargetCadence(): Double = when (phase) {
        Phase.HIGH_INTENSITY -> targetCadenceHigh
        Phase.RECOVERY -> targetCadenceRecovery
        Phase.FINISHED -> targetCadenceHigh
    }

    /**
     * 現フェーズの目標 cadence を delta だけ動かす (高強度なら high、回復なら recovery)。
     * 高強度 > 回復 + 最小ギャップを保つよう clamp。永続化はせずセッション内のみ有効。
     * 次 cycle 完了時の自動制御ループが上書きしうる。
     */
    fun adjustActiveTargetCadence(delta: Double): Double {
        when (phase) {
            Phase.HIGH_INTENSITY -> {
                targetCadenceHigh = (targetCadenceHigh + delta)
                    .coerceIn(targetCadenceRecovery + MIN_CADENCE_GAP, MAX_SPM)
                return targetCadenceHigh
            }
            Phase.RECOVERY -> {
                targetCadenceRecovery = (targetCadenceRecovery + delta)
                    .coerceIn(MIN_SPM, targetCadenceHigh - MIN_CADENCE_GAP)
                return targetCadenceRecovery
            }
            Phase.FINISHED -> return targetCadenceHigh
        }
    }

    /**
     * 高強度フェーズ完了時の制御ループ。pace-metric note §ペースをどう決めるか:
     *   anchor = phase 中で実測した cadence の median (= ユーザが実際に踏んだ pace)。
     *   observed < d_min: new_target = anchor - k × (d_min - observed)   (速すぎ → anchor より遅め推奨)
     *   observed > d_max: new_target = anchor + k × (observed - d_max)   (遅すぎ → anchor より速め推奨)
     *   else:             new_target = anchor                            (sweet spot: 実測 pace を採用)
     *
     * 実測 cadence サンプルが無い場合は target を維持 (= 旧 target-anchor と同じ fallback)。
     */
    private fun updateTargetCadenceHigh(observed: Duration) {
        val anchor = highCadenceSamples.medianOrNull() ?: targetCadenceHigh
        targetCadenceHigh = applyControlLoop(
            anchor = anchor,
            observed = observed,
            dMin = config.cadenceTargetHighDurationMin,
            dMax = config.cadenceTargetHighDurationMax,
        ).coerceIn(targetCadenceRecovery + MIN_CADENCE_GAP, MAX_SPM)
        highCadenceSamples.clear()
    }

    /** 回復フェーズ完了時の制御ループ (高強度側の鏡像。窓は 30〜75s) */
    private fun updateTargetCadenceRecovery(observed: Duration) {
        val anchor = recoveryCadenceSamples.medianOrNull() ?: targetCadenceRecovery
        targetCadenceRecovery = applyControlLoop(
            anchor = anchor,
            observed = observed,
            dMin = config.cadenceTargetRecoveryDurationMin,
            dMax = config.cadenceTargetRecoveryDurationMax,
        ).coerceIn(MIN_SPM, targetCadenceHigh - MIN_CADENCE_GAP)
        recoveryCadenceSamples.clear()
    }

    private fun applyControlLoop(
        anchor: Double,
        observed: Duration,
        dMin: Duration,
        dMax: Duration,
    ): Double {
        val k = config.cadenceControlGain
        return when {
            observed < dMin -> anchor - k * (dMin - observed).inWholeMilliseconds / 1000.0
            observed > dMax -> anchor + k * (observed - dMax).inWholeMilliseconds / 1000.0
            else -> anchor
        }
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val n = size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    companion object {
        const val MIN_THRESHOLD_GAP = 5
        const val MIN_BPM = 100
        const val MAX_BPM = 200
        /** 高強度と回復の cadence 差は最低限残す (機材実測でも約 2 倍差。10 SPM 安全マージン) */
        const val MIN_CADENCE_GAP = 10.0
        const val MIN_SPM = 30.0
        const val MAX_SPM = 220.0
    }

    private fun completeCycle(elapsed: Duration): SessionEvent {
        val recoveryDuration = elapsed - phaseStartedAt
        measuredRecoveryDurations += recoveryDuration
        // pace-metric Phase B: 回復完了時の制御ループ更新
        updateTargetCadenceRecovery(recoveryDuration)

        val base = recoveryBaseline
        if (base == null && baseline != null) {
            recoveryBaseline = recoveryDuration
        } else if (base != null && recoveryDuration >= base * config.recoveryFatigueRatio && currentCycle < finalCycle) {
            finalCycle = currentCycle
            fatigueBrakeFired = true
        }

        if (currentCycle >= finalCycle) {
            phase = Phase.FINISHED
            return SessionEvent.SessionFinished
        }
        phase = Phase.HIGH_INTENSITY
        phaseStartedAt = elapsed
        cycleStartedAt = elapsed
        measureStartedAt = null
        resetStallCheckpoint()
        return SessionEvent.EnterHighIntensity
    }

    private fun enterRecovery(elapsed: Duration) {
        phase = Phase.RECOVERY
        phaseStartedAt = elapsed
        resetStallCheckpoint()
    }

    private fun resetStallCheckpoint() {
        stallCheckpointBpm = null
        stallCheckpointAt = Duration.ZERO
    }

    /**
     * intra-cycle stall 検知。grace period 経過後、`cadenceStallCheckInterval` ごとに
     * 「実測 cadence が active target ± tolerance に居る」かつ「BPM が予想方向に
     * `cadenceStallBpmThreshold` 以上動いていない」場合、active target を nudge する。
     *
     * HIGH:    BPM 上がらない → target を上げ (push 強化)
     * RECOVERY: BPM 下がらない → target を下げ (負荷軽減)
     */
    private fun maybeNudgeForStall(bpm: Int, elapsed: Duration) {
        if (phase == Phase.FINISHED || isWarmingUp) return
        // 高強度は "real HIGH" 開始 (measureStartedAt) から、回復は phase 開始から grace を測る
        val anchorTime = measureStartedAt?.takeIf { phase == Phase.HIGH_INTENSITY } ?: phaseStartedAt
        val phaseElapsed = elapsed - anchorTime
        if (phaseElapsed < config.cadenceStallGracePeriod) return

        val checkpointBpm = stallCheckpointBpm
        if (checkpointBpm == null) {
            // grace 後の最初のサンプル: checkpoint をセット、次回まで判定なし
            stallCheckpointBpm = bpm
            stallCheckpointAt = elapsed
            return
        }
        if (elapsed - stallCheckpointAt < config.cadenceStallCheckInterval) return

        val nearTarget = lastCadenceSample
            ?.let { kotlin.math.abs(it - activeTargetCadence()) <= config.cadenceStallCadenceTolerance }
            ?: false
        val movement = bpm - checkpointBpm
        val stalled = nearTarget && when (phase) {
            Phase.HIGH_INTENSITY -> movement < config.cadenceStallBpmThreshold
            Phase.RECOVERY -> movement > -config.cadenceStallBpmThreshold
            Phase.FINISHED -> false
        }
        if (stalled) {
            when (phase) {
                Phase.HIGH_INTENSITY -> {
                    targetCadenceHigh = (targetCadenceHigh + config.cadenceStallNudge)
                        .coerceAtMost(MAX_SPM)
                }
                Phase.RECOVERY -> {
                    targetCadenceRecovery = (targetCadenceRecovery - config.cadenceStallNudge)
                        .coerceAtLeast(MIN_SPM)
                }
                Phase.FINISHED -> Unit
            }
        }
        stallCheckpointBpm = bpm
        stallCheckpointAt = elapsed
    }
}

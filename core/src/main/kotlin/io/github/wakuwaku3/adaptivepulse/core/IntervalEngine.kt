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
class IntervalEngine(private val config: SessionConfig) {

    var phase: Phase = Phase.HIGH_INTENSITY
        private set

    /** 1 始まり。高強度→回復の完了で 1 サイクル */
    var currentCycle: Int = 1
        private set

    /** 疲労ブレーキで短縮されうる、このセッションの最終サイクル番号 */
    var finalCycle: Int = config.targetCycles
        private set

    /** 疲労判定の基準となる高強度所要時間。未確定の間は null */
    var baseline: Duration? = null
        private set

    /** 実測できたサイクルごとの高強度所要時間 (体力トレンドの源泉として履歴に残す) */
    val highDurations: List<Duration> get() = measuredHighDurations
    private val measuredHighDurations = mutableListOf<Duration>()

    /** 疲労ブレーキが発動したか (履歴用) */
    var fatigueBrakeFired: Boolean = false
        private set

    private var phaseStartedAt: Duration = Duration.ZERO

    // 高強度所要時間は「下限閾値を上向きに超えてから上限到達まで」で測る。
    // セッション開始直後のウォームアップ区間を計測から外し、全サイクルを公平に比較するため。
    private var measureStartedAt: Duration? = null

    /** セッション開始直後、まだ下限閾値を上向きに超えていないウォームアップ区間か */
    val isWarmingUp: Boolean
        get() = phase == Phase.HIGH_INTENSITY && currentCycle == 1 && measureStartedAt == null

    /** 心拍サンプルを処理する。遷移が起きたときだけイベントを 1 つ返す */
    fun onHeartRate(bpm: Int, elapsed: Duration): SessionEvent? {
        // タイムアウトが先に成立していたら強制遷移し、このサンプルは消費する
        // (次サンプルは新フェーズで処理される。サンプル間隔 ~1 秒なので遅延は無視できる)
        onTimePassed(elapsed)?.let { return it }

        return when (phase) {
            Phase.HIGH_INTENSITY -> onHighIntensitySample(bpm, elapsed)
            Phase.RECOVERY -> onRecoverySample(bpm, elapsed)
            Phase.FINISHED -> null
        }
    }

    /** 心拍サンプルが途絶えてもタイムアウト遷移が動くよう、定期 tick からも呼べる */
    fun onTimePassed(elapsed: Duration): SessionEvent? {
        val limit = when (phase) {
            Phase.HIGH_INTENSITY -> config.highPhaseTimeout
            Phase.RECOVERY -> config.recoveryTimeout
            Phase.FINISHED -> return null
        }
        if (elapsed - phaseStartedAt <= limit) return null

        return when (phase) {
            Phase.HIGH_INTENSITY -> {
                // 上限閾値に到達していないので所要時間は測れない。
                // このサイクルは基準設定にも疲労判定にも使わない
                enterRecovery(elapsed)
                SessionEvent.PhaseTimeout
            }
            Phase.RECOVERY -> completeCycle(elapsed, forced = true)
            Phase.FINISHED -> null
        }
    }

    private fun onHighIntensitySample(bpm: Int, elapsed: Duration): SessionEvent? {
        if (measureStartedAt == null && bpm > config.lowerBpm) {
            measureStartedAt = elapsed
        }
        if (bpm <= config.upperBpm) return null

        // 1 サンプルで下限未満→上限超えに飛んだ場合は所要時間 0 として扱う
        val highDuration = elapsed - (measureStartedAt ?: elapsed)
        val event = judgeFatigue(highDuration)
        enterRecovery(elapsed)
        return event
    }

    private fun judgeFatigue(highDuration: Duration): SessionEvent {
        measuredHighDurations += highDuration
        val base = baseline
        if (base == null) {
            // 最初に実測できたサイクルが基準候補。最低基準時間に満たない場合
            // (開始時点で心拍が既に下限閾値付近だった場合など) は妥当な基準と
            // みなさず、次に実測できたサイクルを基準にする (1 回だけ持ち越す)
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
        if (bpm >= config.lowerBpm) return null
        return completeCycle(elapsed, forced = false)
    }

    private fun completeCycle(elapsed: Duration, forced: Boolean): SessionEvent {
        if (currentCycle >= finalCycle) {
            phase = Phase.FINISHED
            return SessionEvent.SessionFinished
        }
        currentCycle++
        phase = Phase.HIGH_INTENSITY
        phaseStartedAt = elapsed
        // 回復で下限を下回ってから始まるので、次の上向き超過を待って計測する
        measureStartedAt = null
        return if (forced) SessionEvent.PhaseTimeout else SessionEvent.EnterHighIntensity
    }

    private fun enterRecovery(elapsed: Duration) {
        phase = Phase.RECOVERY
        phaseStartedAt = elapsed
    }
}

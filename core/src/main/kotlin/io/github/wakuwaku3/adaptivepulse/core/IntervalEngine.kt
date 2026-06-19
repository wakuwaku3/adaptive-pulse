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

    /** 現在有効な上限閾値。セッション中に [adjustActiveThreshold] で動かせるが、永続化はされない */
    var upperBpm: Int = config.upperBpm
        private set

    /** 現在有効な下限閾値。セッション中に [adjustActiveThreshold] で動かせるが、永続化はされない */
    var lowerBpm: Int = config.lowerBpm
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

    /** セッション開始直後、まだ下限閾値を上向きに超えていないウォームアップ区間か */
    val isWarmingUp: Boolean
        get() = phase == Phase.HIGH_INTENSITY && currentCycle == 0 && measureStartedAt == null

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
        return SessionEvent.SessionFinished
    }

    private fun onHighIntensitySample(bpm: Int, elapsed: Duration): SessionEvent? {
        if (measureStartedAt == null && bpm > lowerBpm) {
            measureStartedAt = elapsed
        }
        if (bpm <= upperBpm) return null

        // 上限到達 = この高強度フェーズ完了 → cycle カウントをここで進める
        currentCycle++
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
        if (bpm >= lowerBpm) return null
        return completeCycle(elapsed)
    }

    /**
     * 現フェーズで「次の遷移を起こす閾値」を delta だけ動かす。
     * 高強度フェーズ (WARM-UP 含む) では上限、回復フェーズでは下限を対象にする。
     * 上限 > 下限 + [MIN_THRESHOLD_GAP] を常に保ち、それを侵す動きは clamp する。
     * セッション終了後は何もしない。
     * 戻り値は調整後の対象閾値 (clamp 後の値)。永続化はせず、このセッション内だけ有効。
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

    companion object {
        /** チャタリング防止の最小ヒステリシス幅 (要件のデフォルト 15bpm より小さくしすぎない) */
        const val MIN_THRESHOLD_GAP = 5
        const val MIN_BPM = 100
        const val MAX_BPM = 200
    }

    private fun completeCycle(elapsed: Duration): SessionEvent {
        val recoveryDuration = elapsed - phaseStartedAt
        measuredRecoveryDurations += recoveryDuration

        // 回復時間が初回基準の recoveryFatigueRatio 倍を超えたら自律神経の回復鈍化 =
        // 疲労として、次サイクルには進めず終了する (上限到達短縮の鏡像判定)。
        // 基準は「高強度基準が確定したサイクル」の回復時間にすることで、開始時点で
        // 既に下限を超えていた短すぎ高強度 (筋トレ直後ケース) の歪んだ回復時間を避ける。
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
        // 回復で下限を下回ってから始まるので、次の上向き超過を待って計測する
        measureStartedAt = null
        return SessionEvent.EnterHighIntensity
    }

    private fun enterRecovery(elapsed: Duration) {
        phase = Phase.RECOVERY
        phaseStartedAt = elapsed
    }
}

package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 3 段 cadence ソース (STEPS_PER_MINUTE / STEPS_TOTAL 差分 / 加速度 peak) のうち
 * セッション内で使う 1 つを **実走開始から discovery 窓のあいだ** で確定させる状態マシン。
 *
 * 旧実装は「最初の emit」で discovery を開始していたため、watch 装着 → start タップ →
 * 機材にまたがる前の歩行で tier 1 が fresh になり 15 秒で確定してしまうことがあった
 * (= 実走前にロック完了)。本実装では [startDiscovery] を呼ぶまで discovery は始まらず、
 * 呼び出し側 (= HighIntensity の warm-up を抜けた時点) が明示的にトリガする。
 *
 * 動作:
 *   - [observe] で各 tier の最新 emit 時刻を蓄積する (warm-up 中の emit も freshness の材料には使う)。
 *   - [startDiscovery] が呼ばれるまで [currentTier] は null を返す (= 計測しない)。
 *   - startDiscovery 後の discovery 窓内: 鮮度内で最良 priority の tier を返す (lock しない、上振れ歓迎)。
 *   - 窓経過時に現時点の最良 fresh tier で確定。fresh が無ければ確定せず次の emit を待つ。
 *   - 確定後: 確定 tier が fresh ならそれを、stale なら null。
 *
 * Android 非依存 (`:core` 内、JVM 単体テストで検証)。
 */
class TieredCadenceLock(
    private val discoveryWindow: Duration = 30.seconds,
    private val freshness: Map<CadenceTier, Duration> = DEFAULT_FRESHNESS,
) {

    private val lastFireAt: MutableMap<CadenceTier, Duration> = HashMap()
    private var discoveryStartedAt: Duration? = null
    private var locked: CadenceTier? = null

    /**
     * 各 tier の最新 emit 時刻を受け取る。startDiscovery 前でも記録するため
     * 「discovery 開始直後にすでに fresh な tier」を即採用できる。
     */
    fun observe(tier: CadenceTier, at: Duration) {
        lastFireAt[tier] = at
    }

    /**
     * discovery 窓を開始する。実走 (warm-up 終了) 時点を起点にすることで、
     * 「準備中の歩行で tier 1 が fresh → 即ロック」を防ぐ。複数回呼ばれても
     * 最初の値だけが有効。
     */
    fun startDiscovery(at: Duration) {
        if (discoveryStartedAt == null) discoveryStartedAt = at
    }

    /** 確定済みの tier (= ロック後)。discovery 未開始 / 中は null */
    val lockedTier: CadenceTier? get() = locked

    /** now の時点で採用すべき tier。null は「採用可能な tier 無し or discovery 未開始」 */
    fun currentTier(now: Duration): CadenceTier? {
        locked?.let { return if (isFresh(it, now)) it else null }
        val started = discoveryStartedAt ?: return null
        val best = bestFreshTier(now)
        if (now - started >= discoveryWindow) {
            if (best != null) {
                locked = best
                return best
            }
        }
        return best
    }

    private fun isFresh(tier: CadenceTier, now: Duration): Boolean {
        val at = lastFireAt[tier] ?: return false
        val window = freshness[tier] ?: return false
        return now - at <= window
    }

    private fun bestFreshTier(now: Duration): CadenceTier? =
        CadenceTier.entries
            .filter { isFresh(it, now) }
            .maxByOrNull { it.priority }

    companion object {
        val DEFAULT_FRESHNESS: Map<CadenceTier, Duration> = mapOf(
            CadenceTier.STEPS_PER_MINUTE to 5.seconds,
            CadenceTier.STEPS_TOTAL_DELTA to 10.seconds,
            CadenceTier.ACCELEROMETER to 3.seconds,
        )
    }
}

/** 精度順位 (priority 値が高いほど高精度)。SessionRecord で永続化するので Serializable */
@kotlinx.serialization.Serializable
enum class CadenceTier(val priority: Int) {
    /** tier 1: Health Services `STEPS_PER_MINUTE` (watch の歩行検出が出す瞬時 rate) */
    STEPS_PER_MINUTE(3),

    /** tier 2: Health Services `STEPS_TOTAL` の差分から再構成した SPM */
    STEPS_TOTAL_DELTA(2),

    /** tier 3: 加速度 magnitude の peak detection (歩行検出が効かない動きの保険) */
    ACCELEROMETER(1),
}

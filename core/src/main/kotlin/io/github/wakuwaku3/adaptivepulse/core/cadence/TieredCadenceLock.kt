package io.github.wakuwaku3.adaptivepulse.core.cadence

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 3 段 cadence ソース (STEPS_PER_MINUTE / STEPS_TOTAL 差分 / 加速度 peak) のうち
 * セッション内で使う 1 つを **早期に確定** させる状態マシン。
 *
 * 趣旨: セッション中にロジックがフラフラ切り替わると現在 SPM が階段状に飛ぶ。
 * ユーザは「ロジックが確定したら同じロジックで計測してほしい」と要望しているため、
 * discovery 窓 (デフォルト 15 秒) のあいだに最良の available tier を選び、以降は固定する。
 *
 * 動作:
 *   - 各 tier の最新 emit 時刻を [observe] で受け取る。
 *   - [currentTier] は now を渡して呼び出す:
 *     * discovery 窓内: 鮮度内 (= freshness) で最良 priority の tier を返す。下げない上げる方向のみ。
 *     * discovery 窓経過時: 現時点で fresh な最良 tier に確定 (= 以降この値だけを返す)。
 *     * 確定後: 確定 tier が fresh ならそれを、stale なら null。
 *
 * Android 非依存 (`:core` 内、JVM 単体テストで検証)。
 */
class TieredCadenceLock(
    private val discoveryWindow: Duration = 15.seconds,
    private val freshness: Map<CadenceTier, Duration> = DEFAULT_FRESHNESS,
) {

    private val lastFireAt: MutableMap<CadenceTier, Duration> = HashMap()
    private var firstFireAt: Duration? = null
    private var locked: CadenceTier? = null

    fun observe(tier: CadenceTier, at: Duration) {
        lastFireAt[tier] = at
        if (firstFireAt == null) firstFireAt = at
    }

    /** 確定済みの tier (= ロック後)。discovery 中は null */
    val lockedTier: CadenceTier? get() = locked

    /** now の時点で採用すべき tier。null は「採用可能な tier 無し」 */
    fun currentTier(now: Duration): CadenceTier? {
        locked?.let { return if (isFresh(it, now)) it else null }
        val start = firstFireAt
        val best = bestFreshTier(now)
        if (start != null && now - start >= discoveryWindow) {
            // 窓経過: 現時点の最良で確定。fresh が無ければ null で確定せず次の emit を待つ
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

/** 精度順位 (priority 値が高いほど高精度) */
enum class CadenceTier(val priority: Int) {
    /** tier 1: Health Services `STEPS_PER_MINUTE` (watch の歩行検出が出す瞬時 rate) */
    STEPS_PER_MINUTE(3),

    /** tier 2: Health Services `STEPS_TOTAL` の差分から再構成した SPM */
    STEPS_TOTAL_DELTA(2),

    /** tier 3: 加速度 magnitude の peak detection (歩行検出が効かない動きの保険) */
    ACCELEROMETER(1),
}

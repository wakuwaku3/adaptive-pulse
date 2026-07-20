package io.github.wakuwaku3.adaptivepulse.core.menu

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * メニュー/プログラムの想定所要時間。phone のプログラム編集画面が「朝、何分で終わるか」を
 * 組みながら見せるための概算で、文献値ベースの単純モデルを使う (個人実測は使わない:
 * `.claude/rules/design-basis.md`)。
 *
 * - 高強度 (上り): 心拍 onset kinetics の時定数 τ ≈ 45 秒 (Linnarsson 1974) を線形近似し、
 *   帯幅 30bpm ≒ 3τ (135 秒) となる 4.5 秒/bpm で見積もる。
 * - 回復 (下り): HRR は 1 分目 ~27bpm 降下、以後 ~10bpm/分に鈍る (Imai 1994, Cole 1999)。
 */
object DurationEstimate {

    private const val ONSET_SECONDS_PER_BPM = 4.5
    private const val RECOVERY_FIRST_MINUTE_DROP_BPM = 27.0
    private const val RECOVERY_LATER_DROP_BPM_PER_MIN = 10.0

    fun ofSegment(menu: Menu, amount: Int): Duration = when (val kind = menu.kind) {
        is MenuKind.Interval -> {
            val gap = kind.upperBpm - kind.lowerBpm
            (highSeconds(gap) + recoverySeconds(gap)).seconds * amount
        }
        is MenuKind.Timed -> amount.minutes
    }

    fun ofPlan(plan: SessionPlan): Duration =
        plan.segments.fold(Duration.ZERO) { acc, seg -> acc + ofSegment(seg.menu, seg.amount) }

    private fun highSeconds(gapBpm: Int): Double = gapBpm * ONSET_SECONDS_PER_BPM

    private fun recoverySeconds(gapBpm: Int): Double {
        val gap = gapBpm.toDouble()
        if (gap <= RECOVERY_FIRST_MINUTE_DROP_BPM) return 60.0 * gap / RECOVERY_FIRST_MINUTE_DROP_BPM
        return 60.0 + 60.0 * (gap - RECOVERY_FIRST_MINUTE_DROP_BPM) / RECOVERY_LATER_DROP_BPM_PER_MIN
    }
}

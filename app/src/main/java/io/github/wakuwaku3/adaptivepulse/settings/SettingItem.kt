package io.github.wakuwaku3.adaptivepulse.settings

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * 設定項目の編集メタデータ。値はすべて Int (Stepper の歩進単位) に正規化し、
 * レンジを config 依存で計算することで「上限 > 下限」等の不変条件を編集 UI の
 * 段階で構造的に守る (SessionConfig の require に到達させない)。
 */
enum class SettingItem(
    val title: String,
    val read: (SessionConfig) -> Int,
    val write: (SessionConfig, Int) -> SessionConfig,
    val progression: (SessionConfig) -> IntProgression,
    val format: (Int) -> String,
) {
    UpperBpm(
        title = "UPPER LIMIT",
        read = { it.upperBpm },
        write = { c, v -> c.copy(upperBpm = v) },
        progression = { c -> (c.lowerBpm + 1)..200 },
        format = { "$it bpm" },
    ),
    LowerBpm(
        title = "LOWER LIMIT",
        read = { it.lowerBpm },
        write = { c, v -> c.copy(lowerBpm = v) },
        progression = { c -> 60..<c.upperBpm },
        format = { "$it bpm" },
    ),
    TargetCycles(
        title = "CYCLES",
        read = { it.targetCycles },
        write = { c, v -> c.copy(targetCycles = v) },
        progression = { 1..12 },
        format = { "$it" },
    ),
    FatigueRatio(
        title = "FATIGUE RATIO",
        read = { (it.fatigueRatio * 100).roundToInt() },
        write = { c, v -> c.copy(fatigueRatio = v / 100.0) },
        progression = { IntProgression.fromClosedRange(10, 90, 5) },
        format = { "$it %" },
    ),
    MinBaseline(
        title = "MIN BASELINE",
        read = { it.minBaseline.inWholeSeconds.toInt() },
        write = { c, v -> c.copy(minBaseline = v.seconds) },
        progression = { IntProgression.fromClosedRange(0, 120, 5) },
        format = { "$it s" },
    ),
    HighTimeout(
        title = "HIGH TIMEOUT",
        read = { it.highPhaseTimeout.inWholeSeconds.toInt() },
        write = { c, v -> c.copy(highPhaseTimeout = v.seconds) },
        progression = { IntProgression.fromClosedRange(60, 600, 30) },
        format = ::formatMinSec,
    ),
    RecoveryTimeout(
        title = "RECOVERY TIMEOUT",
        read = { it.recoveryTimeout.inWholeSeconds.toInt() },
        write = { c, v -> c.copy(recoveryTimeout = v.seconds) },
        progression = { IntProgression.fromClosedRange(60, 600, 30) },
        format = ::formatMinSec,
    ),
}

private fun formatMinSec(totalSecs: Int): String = "%d:%02d".format(totalSecs / 60, totalSecs % 60)

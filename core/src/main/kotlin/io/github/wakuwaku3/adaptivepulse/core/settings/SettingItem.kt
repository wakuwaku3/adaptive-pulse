package io.github.wakuwaku3.adaptivepulse.core.settings

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * 設定項目の編集メタデータ。値はすべて Int (Stepper の歩進単位) に正規化し、
 * レンジを config 依存で計算することで不変条件を編集 UI の段階で構造的に守る
 * (SessionConfig の require に到達させない)。
 *
 * ここに並ぶのはメニューに属さないプロファイル/共通設定のみ。閾値・本数・目標 SPM は
 * メニュー (core.menu.Menu) 側の属性で、phone のメニュー編集画面から変更する
 * (docs/stock/requirements.md「メニューとプログラム」)。
 */
enum class SettingItem(
    val title: String,
    val read: (SessionConfig) -> Int,
    val write: (SessionConfig, Int) -> SessionConfig,
    val progression: (SessionConfig) -> IntProgression,
    val format: (Int) -> String,
) {
    FatigueRatio(
        title = "EASE PACE HINT",
        read = { (it.fatigueRatio * 100).roundToInt() },
        write = { c, v -> c.copy(fatigueRatio = v / 100.0) },
        progression = { IntProgression.fromClosedRange(10, 90, 5) },
        format = { "$it %" },
    ),
    RecoveryFatigueRatio(
        title = "STOP HINT",
        read = { (it.recoveryFatigueRatio * 100).roundToInt() },
        write = { c, v -> c.copy(recoveryFatigueRatio = v / 100.0) },
        progression = { IntProgression.fromClosedRange(110, 300, 10) },
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
    AgeYears(
        title = "AGE",
        read = { it.ageYears },
        write = { c, v -> c.copy(ageYears = v) },
        progression = { 18..80 },
        format = { "$it y" },
    ),
    RestingBpm(
        title = "RESTING HR",
        read = { it.restingBpm },
        write = { c, v -> c.copy(restingBpm = v) },
        progression = { 30..100 },
        format = { "$it bpm" },
    ),
}

private fun formatMinSec(totalSecs: Int): String = "%d:%02d".format(totalSecs / 60, totalSecs % 60)

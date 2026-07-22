package io.github.wakuwaku3.adaptivepulse.core.settings

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.math.roundToInt

/**
 * 設定項目の編集メタデータ。値はすべて Int (Stepper の歩進単位) に正規化し、
 * レンジを config 依存で計算することで不変条件を編集 UI の段階で構造的に守る
 * (SessionConfig の require に到達させない)。
 *
 * ここに並ぶのはメニューに属さないプロファイル/共通設定のみ。閾値・本数・目標 SPM・
 * 基準ガード・タイムアウトはメニュー (core.menu.Menu) 側の属性で、phone のメニュー
 * 編集画面から変更する (docs/stock/requirements.md「メニューとプログラム」)。
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

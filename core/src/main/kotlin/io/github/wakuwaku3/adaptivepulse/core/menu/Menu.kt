package io.github.wakuwaku3.adaptivepulse.core.menu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * トレーニング定義の中間単位「メニュー」(docs/stock/requirements.md「メニューとプログラム」)。
 * cycle < menu < program の階層で、menu 単体でもセッションとして実行できる。
 */
@Serializable
data class Menu(
    val id: String,
    val name: String,
    val kind: MenuKind,
) {
    init {
        require(id.isNotBlank()) { "menu id は空にしない" }
        require(name.isNotBlank()) { "menu name は空にしない" }
    }
}

/**
 * メニューの 2 型。心拍トリガー型は既存のインターバル体験 (上限到達→回復、下限到達→高強度)、
 * 時間制は「帯に収めて時間を過ごす」体験で疲労提案・タイムアウトを持たない。
 */
@Serializable
sealed interface MenuKind {

    /** 心拍トリガー型: 上限/下限の閾値往復を cycles 本 */
    @Serializable
    @SerialName("interval")
    data class Interval(
        val upperBpm: Int,
        val lowerBpm: Int,
        val cycles: Int,
        val targetCadenceHigh: Int = 130,
        val targetCadenceRecovery: Int = 90,
    ) : MenuKind {
        init {
            require(upperBpm > lowerBpm) { "上限閾値 ($upperBpm) は下限閾値 ($lowerBpm) より大きいこと" }
            require(cycles >= 1) { "本数は 1 以上" }
            // SessionConfig の require と同じ不変条件 (PlanEngine が config.copy で流し込むため)
            require(targetCadenceHigh > targetCadenceRecovery) {
                "高強度 cadence ($targetCadenceHigh) は回復 cadence ($targetCadenceRecovery) より速いこと"
            }
            require(targetCadenceHigh in 60..220) { "高強度 cadence は 60〜220 SPM の範囲" }
            require(targetCadenceRecovery in 30..180) { "回復 cadence は 30〜180 SPM の範囲" }
        }
    }

    /**
     * 時間制: 心拍の帯 (上限・下限) に収めたまま minutes 過ごす。
     * lowerBpm = null は「下限なし」(ウォームアップのように上だけ抑える用途)。
     */
    @Serializable
    @SerialName("timed")
    data class Timed(
        val upperBpm: Int,
        val lowerBpm: Int? = null,
        val minutes: Int,
        val targetCadence: Int = 90,
    ) : MenuKind {
        init {
            require(minutes >= 1) { "分数は 1 以上" }
            require(lowerBpm == null || upperBpm > lowerBpm) {
                "帯上限 ($upperBpm) は帯下限 ($lowerBpm) より大きいこと"
            }
            require(targetCadence in 30..220) { "cadence は 30〜220 SPM の範囲" }
        }
    }
}

/** 配置量 (心拍トリガー型は本数 / 時間制は分数) のデフォルト値 */
val MenuKind.defaultAmount: Int
    get() = when (this) {
        is MenuKind.Interval -> cycles
        is MenuKind.Timed -> minutes
    }

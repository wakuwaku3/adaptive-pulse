package io.github.wakuwaku3.adaptivepulse.core

import kotlin.math.roundToInt

/**
 * 心拍ゾーンを年齢と安静時心拍から導出する Karvonen 式 (心拍予備能ベース)。
 *
 * 最大心拍は Tanaka 式 (HRmax = 208 − 0.7 × age, Tanaka 2001) で推定し、
 * 目標心拍は restingBpm + intensity × (HRmax − restingBpm) で計算する。
 *
 * Android 非依存の純関数。SessionConfig のデフォルト値導出と、設定 UI からの
 * 「年齢と安静時心拍に合わせて閾値を再計算する」操作の両方から呼ぶ想定。
 */
object HeartRateZones {

    /** 高強度フェーズ上限のデフォルト強度 (%HRR)。要件 docs/stock/requirements.md の 86% に揃える */
    const val DEFAULT_UPPER_INTENSITY = 0.86

    /** 回復フェーズ復帰のデフォルト強度 (%HRR)。要件の 77% に揃える */
    const val DEFAULT_LOWER_INTENSITY = 0.77

    /** Tanaka 式による最大心拍推定 (208 − 0.7 × 年齢) */
    fun tanakaMaxHr(ageYears: Int): Int = (208 - 0.7 * ageYears).roundToInt()

    /**
     * Karvonen 式による目標心拍。intensity は 0.0〜1.0 (%HRR)。
     * %HRmax と違って安静時心拍を引いた予備能を基準にするため、
     * 安静時心拍の個人差を反映できる (低い人は上限が相対的に高くなる)。
     */
    fun karvonen(ageYears: Int, restingBpm: Int, intensity: Double): Int {
        val maxHr = tanakaMaxHr(ageYears)
        val reserve = maxHr - restingBpm
        return (restingBpm + intensity * reserve).roundToInt()
    }

    /** プロファイルから導出した上限閾値 (デフォルト強度) */
    fun defaultUpperBpm(ageYears: Int, restingBpm: Int): Int =
        karvonen(ageYears, restingBpm, DEFAULT_UPPER_INTENSITY)

    /** プロファイルから導出した下限閾値 (デフォルト強度) */
    fun defaultLowerBpm(ageYears: Int, restingBpm: Int): Int =
        karvonen(ageYears, restingBpm, DEFAULT_LOWER_INTENSITY)
}

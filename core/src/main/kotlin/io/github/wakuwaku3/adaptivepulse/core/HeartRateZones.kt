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

    /**
     * 高強度フェーズ上限のデフォルト強度 (%HRR)。
     *
     * 旧 0.86 (≒91% HRmax, T@VO₂max 帯) は教科書的 HIIT (時間 anchor で次フェーズに進む) を
     * 前提とした値だが、本アプリは **HR anchor** (上限到達で初めて回復に進む) のため、
     * 86% を置くと「到達できないサイクルが永遠に終わらない / timeout で疲労ブレーキ発動」と
     * いう病的挙動が出やすかった (FB 2026-06-22)。
     *
     * 0.77 (≒82% HRmax) は ACSM ガイドラインの "vigorous" 帯下端 (中強度〜高強度の境界) で、
     * 「数サイクル繰り返しても届く閾値」として再設定したもの。
     */
    const val DEFAULT_UPPER_INTENSITY = 0.77

    /**
     * 回復フェーズ復帰のデフォルト強度 (%HRR)。Norwegian 4×4 等の長尺 HIIT が
     * 回復目標とする 60-70% HRmax 帯 (≒0.45-0.62 HRR) の中央付近に揃える
     * (Wisløff 2007, Helgerud 2007, Buchheit & Laursen 2013)。
     */
    const val DEFAULT_LOWER_INTENSITY = 0.60

    /** Tanaka 式による最大心拍推定 (208 − 0.7 × 年齢) */
    fun tanakaMaxHr(ageYears: Int): Int = (208 - 0.7 * ageYears).roundToInt()

    /**
     * %HRmax → bpm。プリセットメニューのように文献が %HRmax で強度を定義するケース用
     * (Karvonen の %HRR とは基準が違うことに注意)。
     */
    fun percentOfMax(ageYears: Int, fraction: Double): Int =
        (fraction * tanakaMaxHr(ageYears)).roundToInt()

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

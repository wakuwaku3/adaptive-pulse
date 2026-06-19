package io.github.wakuwaku3.adaptivepulse.core.sync

import kotlinx.serialization.Serializable

/**
 * 1 日分の集計健康指標。Health Connect 経由で日次に取り込み、JSON エクスポートや
 * 将来の Firestore 保存の共通シェイプとして使う。データ源は:
 *  - Pixel Watch: heart rate / sleep / steps / calories
 *  - 体重計: weight / bodyFatPct / leanBodyMassKg
 *  - 食事ログアプリ (あすけん 等): intakeKcal / proteinG / fatG / carbsG
 *  - 任意の HC writer: heightCm
 *
 * 取得できなかった項目は null で、復元時もそのまま欠損として残す (推定で埋めない)。
 */
@Serializable
data class DailyHealthRecord(
    /** ISO 8601 (YYYY-MM-DD)、ローカルタイムゾーンの暦日 */
    val date: String,
    val restingHeartRateBpm: Int? = null,
    val hrvRmssdMs: Double? = null,
    /** 平均心拍 (起きている間の平均ではなく、当日に記録された全 HR サンプルの平均) */
    val avgHeartRateBpm: Int? = null,
    val sleepDurationMin: Long? = null,
    val sleepDeepMin: Long? = null,
    val sleepRemMin: Long? = null,
    val sleepLightMin: Long? = null,
    val sleepAwakeMin: Long? = null,
    val steps: Long? = null,
    val activeCaloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanBodyMassKg: Double? = null,
    val heightCm: Double? = null,
    val intakeKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
)

/** エクスポート 1 ファイルの top-level シェイプ */
@Serializable
data class HealthDataExport(
    val schema: Int = 1,
    /** UNIX epoch millis でのエクスポート時刻 */
    val exportedAtMs: Long,
    /** 含まれる日付の範囲 (inclusive) */
    val fromDate: String,
    val toDate: String,
    val dailyMetrics: List<DailyHealthRecord>,
    /** 既存 SessionRecord (本アプリのワークアウト履歴) を同梱する */
    val sessions: List<SessionRecord>,
)

package io.github.wakuwaku3.adaptivepulse.core.sync

import kotlinx.serialization.Serializable

/**
 * 1 日分の集計健康指標。Health Connect 経由で日次に取り込み、JSON エクスポートや
 * Firestore 保存の共通シェイプとして使う。データ源は:
 *  - Pixel Watch: heart rate / sleep / steps / calories / SpO2 / 呼吸数 / 皮膚温
 *  - 体重計: weight / bodyFatPct / leanBodyMassKg
 *  - 食事ログアプリ (あすけん 等): intakeKcal / proteinG / fatG / carbsG / fiberG / sugarG / sodiumMg
 *  - 任意の HC writer: heightCm / distance / floors / elevation / BMR
 *
 * 取得できなかった項目は null で、復元時もそのまま欠損として残す (推定で埋めない)。
 *
 * 後方互換: 旧版で書かれた document は新フィールドが欠落するが、`@Serializable` のデフォルト
 * null を経由してロードできる (`ignoreUnknownKeys = true`)。
 */
@Serializable
data class DailyHealthRecord(
    /** ISO 8601 (YYYY-MM-DD)、ローカルタイムゾーンの暦日 */
    val date: String,
    // 心拍
    val restingHeartRateBpm: Int? = null,
    val hrvRmssdMs: Double? = null,
    /** 平均心拍 (当日に記録された全 HR サンプルの平均) */
    val avgHeartRateBpm: Int? = null,
    val minHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    // 睡眠 (分)
    val sleepDurationMin: Long? = null,
    val sleepDeepMin: Long? = null,
    val sleepRemMin: Long? = null,
    val sleepLightMin: Long? = null,
    val sleepAwakeMin: Long? = null,
    // 活動
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val floorsClimbed: Double? = null,
    val elevationGainedMeters: Double? = null,
    val activeCaloriesKcal: Double? = null,
    val totalCaloriesKcal: Double? = null,
    val basalCaloriesKcal: Double? = null,
    /**
     * `core.calories.TdeeCalc` が再計算した TDEE。HC `totalCaloriesKcal` は watch/Fitbit
     * の overcount を含むので、deficit 表示はこちらを正にする。
     * 詳細は docs/notes/20260621__tdee-recompute/index.md。
     */
    val tdeeKcal: Double? = null,
    /** TDEE のうち「BMR + 歩数 NEAT を除いた運動 extra」。自社 HIIT + HC ExerciseSession 合算。 */
    val exerciseExtraKcal: Double? = null,
    // 体組成
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanBodyMassKg: Double? = null,
    val heightCm: Double? = null,
    // 食事
    val intakeKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    // バイタル
    val spo2AvgPct: Double? = null,
    val spo2MinPct: Double? = null,
    val respiratoryRateAvg: Double? = null,
    val skinTemperatureDeltaC: Double? = null,
    /**
     * 主要指標のデータソース別内訳。HC には端末を横断してアクセスできないため、
     * 分析に使いうる読み取り済みデータは (大容量の時系列を除き) すべてここに同梱して
     * Firestore まで届ける。空リストは null に正規化する ([isEmpty] 判定を保つため)。
     */
    val breakdown: List<MetricSourceValue>? = null,
    /** 他アプリ (Strava / Fit 等) が HC に書いた、その日に重なる運動セッション */
    val externalSessions: List<ExternalExerciseSession>? = null,
) {
    /**
     * `date` 以外のすべてのフィールドが null。HC が一時的に応答を返さない / その日にデータが
     * 一切ない、で発生する。Firestore は LWW の full replace なので、これを書くと過去に
     * 正しく入っていた行を null まみれに上書きしてしまう。書き込み前に弾く目印に使う。
     */
    val isEmpty: Boolean
        get() = this == DailyHealthRecord(date = date)
}

/**
 * 指標 1 種 × データソース 1 件の値。同じ指標 (例: totalCalories) を watch / phone / Fit が
 * 別々に HC へ書いているのを保持し、端末間乖離の分析に使う。
 * metricKey は mobile 側 `HealthDataSource.METRIC_*` の文字列。
 */
@Serializable
data class MetricSourceValue(
    val metricKey: String,
    val sourcePackage: String,
    val value: Double,
)

/** 他アプリが HC に書いた運動セッション。exerciseType は HC の ExerciseSessionRecord 定数 */
@Serializable
data class ExternalExerciseSession(
    val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val exerciseType: Int,
    val title: String? = null,
    val sourcePackage: String,
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

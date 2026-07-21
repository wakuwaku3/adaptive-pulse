package io.github.wakuwaku3.adaptivepulse.mobile.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Health Connect から取り込んだダッシュボード表示用データのローカルキャッシュ。
 *
 * 設計方針: Firestore には日次集約 (per-source 内訳・他アプリ運動セッション同梱) を上げる。
 * 大容量の時系列 (HR / Vital) は HC が原本なので Room に閉じる
 * (機種変時はローカルキャッシュが消えても HC から再同期で復元可能)。
 * 詳細は `docs/notes/20260620__dashboard-redesign/index.md`。
 */

/** 1 日分の集約スナップショット (date は ISO YYYY-MM-DD) */
@Entity(tableName = "daily_snapshot")
data class DailySnapshotEntity(
    @PrimaryKey val date: String,
    val syncedAtMs: Long,
    /**
     * この行を Firestore へ upsert した時刻。null = 未反映。行を書き直すと null に戻り
     * 再アップロード対象になる。同期 worker が途中停止しても、次の実行 (通常/遡及どちらでも)
     * が未反映行を拾って続きから上げられるようにするための再開マーク。
     */
    val uploadedAtMs: Long? = null,
    /**
     * 例外ゼロのクリーン読みで書かれた時刻。null = 読み取り失敗時の空マーカー行。
     * クリーン読みの行は「HC の現状そのもの」なので、空でも Firestore へ反映してよい
     * (HC 側で削除されたデータの伝播)。Resync (HC を正とする全再読) の再開判定にも使う。
     */
    val verifiedAtMs: Long? = null,
    // 心拍
    val restingHeartRateBpm: Int? = null,
    val hrvRmssdMs: Double? = null,
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
    /** 自前計算した TDEE。watch overcount を含まない。詳細 [DailyHealthRecord.tdeeKcal]。 */
    val tdeeKcal: Double? = null,
    val exerciseExtraKcal: Double? = null,
    // 体組成
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val leanBodyMassKg: Double? = null,
    val heightCm: Double? = null,
    // 食事 (Asken etc.)
    val intakeKcal: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    // バイタル (Pixel Watch)
    val spo2AvgPct: Double? = null,
    val spo2MinPct: Double? = null,
    val respiratoryRateAvg: Double? = null,
    val skinTemperatureDeltaC: Double? = null,
    /** [io.github.wakuwaku3.adaptivepulse.core.sync.MetricSourceValue] のリストを JSON 化した列 */
    val breakdownJson: String? = null,
    /** [io.github.wakuwaku3.adaptivepulse.core.sync.ExternalExerciseSession] のリストを JSON 化した列 */
    val externalSessionsJson: String? = null,
)

/**
 * 指標 1 種 × データソース 1 件 × 1 日 の breakdown。
 * 同じ指標 (例: totalCalories) を watch / phone / Fit が別々に書いているのを保持する。
 */
@Entity(tableName = "metric_by_source", primaryKeys = ["date", "metricKey", "sourcePackage"])
data class MetricBySourceEntity(
    val date: String,
    val metricKey: String,
    val sourcePackage: String,
    val value: Double,
)

/** 心拍時系列 (容量制御のため今日 + 昨日のみ保持) */
@Entity(tableName = "heart_rate_sample", primaryKeys = ["timestampMs", "sourcePackage"])
data class HeartRateSampleEntity(
    val timestampMs: Long,
    val sourcePackage: String,
    val bpm: Int,
)

enum class VitalKind { SPO2, RESPIRATORY_RATE, SKIN_TEMPERATURE_DELTA }

/** SpO2 / 呼吸数 / 皮膚温の時系列 */
@Entity(tableName = "vital_sample", primaryKeys = ["timestampMs", "kind"])
data class VitalSampleEntity(
    val timestampMs: Long,
    val kind: VitalKind,
    val value: Double,
    val sourcePackage: String,
)

/** 他アプリ (Strava / Fit / 体組成計アプリ等) が HC に書いた運動セッション */
@Entity(tableName = "exercise_session")
data class ExerciseSessionEntity(
    @PrimaryKey val id: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val exerciseType: Int,
    val title: String?,
    val sourcePackage: String,
)

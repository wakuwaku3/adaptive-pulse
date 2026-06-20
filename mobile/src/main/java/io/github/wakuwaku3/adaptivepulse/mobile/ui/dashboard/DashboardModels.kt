package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.ui.graphics.Color
import io.github.wakuwaku3.adaptivepulse.mobile.store.DailySnapshotEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * Today カード + ミニチャートで使う派生指標。Room の生エンティティから 1 か所で計算しておき、
 * UI 側は表示するだけにする。
 *
 * 数値モデル:
 *  - TDEE は HC `totalCaloriesKcal` (BMR 込み)
 *  - deficit = TDEE - intake
 *  - BMR_est は Mifflin-St Jeor
 *  - BMI = weight / (height_m)^2
 */
data class DashboardComputed(
    val date: String,
    val weightKg: Double?,
    val heightCm: Double?,
    val bmi: Double?,
    val tdeeKcal: Double?,
    val intakeKcal: Double?,
    val deficitKcal: Double?,
    val proteinG: Double?,
    val fatG: Double?,
    val carbsG: Double?,
    val sleepHours: Double?,
    val sleepDeepMin: Long?,
    val sleepRemMin: Long?,
    val hrvMs: Double?,
    val restingHrBpm: Int?,
    val steps: Long?,
    val bmrEstKcal: Double?,
    val proteinPerKg: Double?,
    val spo2AvgPct: Double?,
)

fun DailySnapshotEntity.computed(ageYears: Int = 39): DashboardComputed {
    val bmr = bmrMifflinStJeor(weightKg, heightCm, ageYears)
    val deficit = if (totalCaloriesKcal != null && intakeKcal != null) totalCaloriesKcal - intakeKcal else null
    val sleepHours = sleepDurationMin?.let { it / 60.0 }
    val protKg = if (proteinG != null && weightKg != null) proteinG / weightKg else null
    val bmi = if (weightKg != null && heightCm != null && heightCm > 0) {
        val m = heightCm / 100.0
        weightKg / (m * m)
    } else null
    return DashboardComputed(
        date = date,
        weightKg = weightKg,
        heightCm = heightCm,
        bmi = bmi,
        tdeeKcal = totalCaloriesKcal,
        intakeKcal = intakeKcal,
        deficitKcal = deficit,
        proteinG = proteinG,
        fatG = fatG,
        carbsG = carbsG,
        sleepHours = sleepHours,
        sleepDeepMin = sleepDeepMin,
        sleepRemMin = sleepRemMin,
        hrvMs = hrvRmssdMs,
        restingHrBpm = restingHeartRateBpm,
        steps = steps,
        bmrEstKcal = bmr,
        proteinPerKg = protKg,
        spo2AvgPct = spo2AvgPct,
    )
}

private fun bmrMifflinStJeor(weightKg: Double?, heightCm: Double?, ageYears: Int): Double? {
    if (weightKg == null || heightCm == null) return null
    return 10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5
}

/**
 * チャート背景の「適正帯」表現。[topInclusive], [bottomInclusive] は値の範囲 (kcal / kg / bpm 等)、
 * チャート側で y 軸スケールに変換して描画する。
 */
data class Band(
    val label: String,
    val from: Double,
    val to: Double,
    val color: Color,
)

private val GoodBand = MobileColors.Recover.copy(alpha = 0.12f)
private val NeutralBand = MobileColors.Done.copy(alpha = 0.10f)
private val BadBand = MobileColors.High.copy(alpha = 0.12f)
private val MildGoodBand = MobileColors.Recover.copy(alpha = 0.06f)
private val MildBadBand = MobileColors.High.copy(alpha = 0.06f)

/** WHO 基準の BMI 区分 */
object Bmi {
    val bands = listOf(
        Band("やせ", 0.0, 18.5, NeutralBand),
        Band("普通", 18.5, 25.0, GoodBand),
        Band("過体重", 25.0, 30.0, NeutralBand),
        Band("肥満 1 度", 30.0, 35.0, BadBand),
        Band("肥満 2 度+", 35.0, 60.0, BadBand),
    )

    fun categoryOf(bmi: Double): String = bands.firstOrNull { bmi in it.from..it.to }?.label ?: "—"
}

/** 1 日あたりカロリー赤字 (deficit) — 上下に対称帯 */
object Deficit {
    val bands = listOf(
        Band("大幅サープラス", -10_000.0, -500.0, BadBand),
        Band("微サープラス", -500.0, 0.0, MildBadBand),
        Band("微赤字", 0.0, 500.0, MildGoodBand),
        Band("適正赤字", 500.0, 10_000.0, GoodBand),
    )

    fun categoryOf(deficit: Double): String {
        // deficit は TDEE - intake (赤字なら正)
        return bands.firstOrNull { deficit in it.from..it.to }?.label ?: "—"
    }
}

/** 1 日あたり歩数 — WHO/CDC 系の目安 */
object Steps {
    val bands = listOf(
        Band("座位中心", 0.0, 5_000.0, BadBand),
        Band("低活動", 5_000.0, 7_500.0, NeutralBand),
        Band("ふつう", 7_500.0, 10_000.0, GoodBand),
        Band("活動的", 10_000.0, 50_000.0, GoodBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/** タンパク質 g/kg。減量中は 1.6+ が筋量維持の目安 */
object Protein {
    val bands = listOf(
        Band("不足", 0.0, 1.0, BadBand),
        Band("低め", 1.0, 1.6, NeutralBand),
        Band("適正", 1.6, 2.2, GoodBand),
        Band("過多", 2.2, 5.0, NeutralBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/** 睡眠時間 (時間)。NIH 成人推奨 7-9h */
object Sleep {
    val bands = listOf(
        Band("不足", 0.0, 6.0, BadBand),
        Band("やや短い", 6.0, 7.0, NeutralBand),
        Band("適正", 7.0, 9.0, GoodBand),
        Band("過剰", 9.0, 14.0, NeutralBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/** 安静時心拍 (bpm) — Mayo Clinic 系の目安 */
object RestingHr {
    val bands = listOf(
        Band("運動者並み", 0.0, 60.0, GoodBand),
        Band("良好", 60.0, 70.0, GoodBand),
        Band("普通", 70.0, 80.0, NeutralBand),
        Band("やや高", 80.0, 90.0, BadBand),
        Band("高", 90.0, 200.0, BadBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/** SpO2 (%) — 95% 以上が正常、それ未満は注意 */
object Spo2 {
    val bands = listOf(
        Band("低酸素", 80.0, 90.0, BadBand),
        Band("注意", 90.0, 95.0, NeutralBand),
        Band("正常", 95.0, 100.0, GoodBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/**
 * 体重の適正帯。BMI バンドを身長から逆算する (kg = BMI × (身長 m)²)。
 * 身長が不明なら空配列を返す = チャートには帯を描かない。
 */
fun weightBandsForHeight(heightCm: Double?): List<Band> {
    if (heightCm == null || heightCm <= 0) return emptyList()
    val m = heightCm / 100.0
    val mm = m * m
    return Bmi.bands.map { Band(it.label, it.from * mm, it.to * mm, it.color) }
}

fun weightCategoryFor(weightKg: Double, heightCm: Double?): String? {
    if (heightCm == null || heightCm <= 0) return null
    val mm = (heightCm / 100.0).let { it * it }
    val bmi = weightKg / mm
    return Bmi.categoryOf(bmi)
}

/**
 * 心拍ゾーン (本アプリの session 設定基準)。
 * - upper 以上 = 高強度
 * - lower〜upper = 中強度
 * - lower 未満 = 低強度
 */
fun hrZonesFor(upperBpm: Int, lowerBpm: Int): List<Band> = listOf(
    Band("低強度", 30.0, lowerBpm.toDouble(), MildGoodBand),
    Band("中強度", lowerBpm.toDouble(), upperBpm.toDouble(), NeutralBand),
    Band("高強度", upperBpm.toDouble(), 220.0, BadBand),
)

fun hrCategoryFor(bpm: Double, upperBpm: Int, lowerBpm: Int): String = when {
    bpm >= upperBpm -> "高強度"
    bpm >= lowerBpm -> "中強度"
    else -> "低強度"
}

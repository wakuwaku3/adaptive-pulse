package io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard

import androidx.compose.ui.graphics.Color
import io.github.wakuwaku3.adaptivepulse.mobile.store.DailySnapshotEntity
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors

/**
 * Today カード + ミニチャートで使う派生指標。Room の生エンティティから 1 か所で計算しておき、
 * UI 側は表示するだけにする。
 *
 * 数値モデル:
 *  - TDEE は `DailySnapshotEntity.tdeeKcal` (`core.calories.TdeeCalc` が再計算した値)
 *    HC `totalCaloriesKcal` は watch/Fitbit overcount を含むので採用しない
 *    詳細は docs/notes/20260621__tdee-recompute/
 *  - deficit = intake - TDEE (負 = 痩せ方向)
 *  - BMR_est は Mifflin-St Jeor
 *  - BMI = weight / (height_m)^2
 */
data class DashboardComputed(
    val date: String,
    val weightKg: Double?,
    val heightCm: Double?,
    val bmi: Double?,
    val tdeeKcal: Double?,
    /** TDEE のうち BMR + 歩数を除いた運動 extra (自社 HIIT + HC 他アプリ session 合算)。 */
    val exerciseExtraKcal: Double?,
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
    /** タンパク質のアンカー体重あたり g/kg ([proteinAnchorWeightKg] 基準) */
    val proteinPerTargetKg: Double?,
    val fatPerKg: Double?,
    val carbsPerKg: Double?,
    val spo2AvgPct: Double?,
    /** 明示設定された目標体重 (kg)。体重チャートの目標ラインに使う。null = 未設定 */
    val targetWeightKg: Double?,
    /**
     * タンパク質帯のアンカー体重: 目標体重、未設定なら身長から BMI 22 標準体重。
     * 過体重では現体重あたりの g/kg が過大になるため、目標体重にアンカーする
     * (docs/stock/nutrition-guidelines.md)。
     */
    val proteinAnchorWeightKg: Double?,
)

fun DailySnapshotEntity.computed(
    ageYears: Int = 39,
    fallbackHeightCm: Double? = null,
    targetWeightKg: Double? = null,
): DashboardComputed {
    // HC `HeightRecord` が無い環境 (Google Fit 切断後など) のため、entity に乗っていない日は
    // SessionConfig.heightCm で補完する。両方 null なら BMI / BMR_est は引き続き "—"。
    val effectiveHeightCm = heightCm ?: fallbackHeightCm
    val bmr = bmrMifflinStJeor(weightKg, effectiveHeightCm, ageYears)
    // TDEE は CalorieEnricher が再計算した値を採用 (HC raw total は watch overcount)。
    // tdeeKcal が null の日 (旧 sync で未計算) は表示も deficit も「—」。
    val deficit = if (tdeeKcal != null && intakeKcal != null) intakeKcal - tdeeKcal else null
    val sleepHours = sleepDurationMin?.let { it / 60.0 }
    val proteinAnchor = targetWeightKg ?: standardWeightKg(effectiveHeightCm)
    val protKg = if (proteinG != null && proteinAnchor != null) proteinG / proteinAnchor else null
    val fatKg = if (fatG != null && weightKg != null) fatG / weightKg else null
    val carbKg = if (carbsG != null && weightKg != null) carbsG / weightKg else null
    val bmi = if (weightKg != null && effectiveHeightCm != null && effectiveHeightCm > 0) {
        val m = effectiveHeightCm / 100.0
        weightKg / (m * m)
    } else null
    return DashboardComputed(
        date = date,
        weightKg = weightKg,
        heightCm = effectiveHeightCm,
        bmi = bmi,
        tdeeKcal = tdeeKcal,
        exerciseExtraKcal = exerciseExtraKcal,
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
        proteinPerTargetKg = protKg,
        fatPerKg = fatKg,
        carbsPerKg = carbKg,
        spo2AvgPct = spo2AvgPct,
        targetWeightKg = targetWeightKg,
        proteinAnchorWeightKg = proteinAnchor,
    )
}

/** BMI 22 (日本肥満学会の標準体重) を身長から逆算する。目標体重が未設定のときの代用アンカー */
fun standardWeightKg(heightCm: Double?): Double? {
    if (heightCm == null || heightCm <= 0) return null
    val m = heightCm / 100.0
    return 22.0 * m * m
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

/**
 * 1 日あたり intake − TDEE。
 * 負 = 赤字 (痩せ方向、緑) / 正 = サープラス (太る方向、赤)。
 */
object Deficit {
    val bands = listOf(
        Band("適正赤字", -10_000.0, -500.0, GoodBand),
        Band("微赤字", -500.0, 0.0, MildGoodBand),
        Band("微サープラス", 0.0, 500.0, MildBadBand),
        Band("大幅サープラス", 500.0, 10_000.0, BadBand),
    )

    fun categoryOf(deficit: Double): String =
        bands.firstOrNull { deficit in it.from..it.to }?.label ?: "—"
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

/**
 * タンパク質 g/kg **目標体重** あたり。減量 × 筋トレ併用の筋量維持は 1.6-2.2 g/kg
 * (Morton 2018 のブレークポイント 1.6, Helms 2014)。過体重では現体重あたりだと過大に
 * なるため目標体重にアンカーする。「不足」境界 1.6 は /analyze の下限と同一
 * (docs/stock/nutrition-guidelines.md)。
 */
object Protein {
    val bands = listOf(
        Band("不足", 0.0, 1.6, BadBand),
        Band("適正", 1.6, 2.2, GoodBand),
        Band("過多", 2.2, 5.0, NeutralBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/**
 * 脂質 g/kg 現体重あたり。減量中の推奨 0.5-1.5 g/kg で、下限 0.5 はホルモン機能維持の
 * 最低ライン (Helms 2014)。「不足」境界 0.5 は /analyze の下限と同一
 * (docs/stock/nutrition-guidelines.md)。
 */
object Fat {
    val bands = listOf(
        Band("不足", 0.0, 0.5, BadBand),
        Band("適正", 0.5, 1.5, GoodBand),
        Band("高め", 1.5, 5.0, NeutralBand),
    )

    fun categoryOf(v: Double): String = bands.firstOrNull { v in it.from..it.to }?.label ?: "—"
}

/** 炭水化物 g/kg。減量で 2-4 g/kg が一般的、極端な低糖質は意図的ならよし */
object Carbs {
    val bands = listOf(
        Band("極低糖質", 0.0, 1.0, NeutralBand),
        Band("低糖質", 1.0, 2.0, NeutralBand),
        Band("ふつう", 2.0, 4.0, GoodBand),
        Band("高糖質", 4.0, 10.0, NeutralBand),
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
 * 栄養素 (g) の絶対量帯を体重から逆算する。チャートを g/kg ではなく g で表示するため、
 * 帯の境界も g に変換しておく。
 */
fun nutrientBandsForWeight(gPerKgBands: List<Band>, weightKg: Double?): List<Band> {
    val w = weightKg ?: return emptyList()
    return gPerKgBands.map { Band(it.label, it.from * w, it.to * w, it.color) }
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

/**
 * 末端移動平均 (trailing moving average)。null 値はウィンドウから除外し、
 * 有効サンプル数がウィンドウの半分未満の点は null (端の値が偏らないように)。
 */
internal fun movingAverage(values: List<Double?>, window: Int): List<Double?> {
    if (window <= 1) return values
    val minSamples = (window / 2).coerceAtLeast(1)
    return values.indices.map { i ->
        val from = maxOf(0, i - window + 1)
        val slice = values.subList(from, i + 1).filterNotNull()
        if (slice.size < minSamples) null else slice.average()
    }
}

/**
 * データ点数に応じた移動平均ウィンドウ。
 * - Day (n≈3): n が少なすぎて MA を出さない (呼び出し側で抑制)
 * - Week (n≈7): 3 日
 * - Month (n≈30): 7 日
 * - Year (n≈365): 14 日 (cap)
 */
internal fun pickMaWindow(n: Int): Int = (n / 4).coerceIn(3, 14)

/** ダッシュボードの表示期間 */
enum class Period(val days: Int, val label: String) {
    DAY(3, "Day"),
    WEEK(7, "Week"),
    MONTH(30, "Month"),
    YEAR(365, "Year"),
}

/**
 * 値が落ちている band の "好ましさ" を 3 色に正規化して返す:
 * 緑 (Recover) = 良好 / 黄 (Done) = 中立 / 赤 (High) = 注意。
 * Today カードの数値色分けで使う。
 */
fun bandStateColor(value: Double?, bands: List<Band>): Color {
    if (value == null) return MobileColors.TextDim
    val band = bands.firstOrNull { value in it.from..it.to } ?: return MobileColors.TextDim
    return when {
        band.color == GoodBand -> MobileColors.Recover
        band.color == BadBand -> MobileColors.High
        band.color == MildGoodBand -> MobileColors.Recover
        band.color == MildBadBand -> MobileColors.High
        else -> MobileColors.Done
    }
}

package io.github.wakuwaku3.adaptivepulse.core.calories

/**
 * 1 日の TDEE を「BMR + 歩数由来 NEAT + 運動 extra」で算出する純関数。
 * 詳細な動機は docs/notes/20260621__tdee-recompute/。
 *
 * HC の `TotalCaloriesBurnedRecord` を直接信頼しない。watch / Fitbit が過大評価
 * (実測比 2-3x) する事例があり、deficit の根拠にできないため。
 */
object TdeeCalc {

    /**
     * @param basalKcal HC `BasalMetabolicRateRecord` aggregate。null なら Mifflin-St Jeor で補う。
     * @param weightKg 体重。null なら NEAT も extra も 0 にして BMR だけ返す。
     * @param heightCm Mifflin-St Jeor フォールバック用。basalKcal が無く height も無いと BMR を出せない。
     * @param ageYears Mifflin-St Jeor フォールバック用 (39 がデフォルト想定)。
     * @param steps 当日の総歩数。null は 0 扱い。
     * @param hcSessions HC 由来の他アプリ運動セッション。
     * @param appSessions 自社 HIIT セッション。HC session と時間が重複したら HC 側を skip する。
     */
    fun compute(
        basalKcal: Double?,
        weightKg: Double?,
        heightCm: Double?,
        ageYears: Int,
        steps: Long?,
        hcSessions: List<HcSession>,
        appSessions: List<AppSession>,
    ): TdeeBreakdown {
        val bmr = basalKcal ?: mifflinStJeor(weightKg, heightCm, ageYears)
        if (bmr == null || weightKg == null) {
            return TdeeBreakdown(bmrKcal = bmr, neatKcal = null, exerciseExtraKcal = null, tdeeKcal = null)
        }
        val kcalPerStep = weightKg * 0.0005
        val neat = (steps ?: 0L) * kcalPerStep

        val appExtra = appSessions.sumOf { extraKcal(ExerciseKind.HIIT, weightKg, it.durationSec) }
        // 自社 HIIT と重なる HC session は二重計上を避けて skip
        val nonOverlap = hcSessions.filter { hc -> appSessions.none { it.overlaps(hc) } }
        val hcExtra = nonOverlap.sumOf { hc ->
            if (hc.kind.stepCovered) 0.0
            else extraKcal(hc.kind, weightKg, durationSecOf(hc))
        }
        val extra = appExtra + hcExtra
        val tdee = bmr + neat + extra
        return TdeeBreakdown(
            bmrKcal = bmr,
            neatKcal = neat,
            exerciseExtraKcal = extra,
            tdeeKcal = tdee,
        )
    }

    /** (MET - 1) × weight × hours: BMR 分を引いた上乗せ分だけを返す。 */
    private fun extraKcal(kind: ExerciseKind, weightKg: Double, durationSec: Long): Double {
        if (durationSec <= 0) return 0.0
        val hours = durationSec / 3600.0
        return (kind.met - 1.0) * weightKg * hours
    }

    private fun durationSecOf(s: HcSession): Long = ((s.endMs - s.startMs) / 1000L).coerceAtLeast(0L)

    private fun AppSession.overlaps(hc: HcSession): Boolean {
        val appEnd = startedAtMs + durationSec * 1000L
        return startedAtMs < hc.endMs && hc.startMs < appEnd
    }

    /** Mifflin-St Jeor (male)。HC basal が null の日のフォールバック。 */
    private fun mifflinStJeor(weightKg: Double?, heightCm: Double?, ageYears: Int): Double? {
        if (weightKg == null || heightCm == null) return null
        return 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears + 5.0
    }

    data class HcSession(val startMs: Long, val endMs: Long, val kind: ExerciseKind)

    data class AppSession(val startedAtMs: Long, val durationSec: Long)

    data class TdeeBreakdown(
        val bmrKcal: Double?,
        val neatKcal: Double?,
        val exerciseExtraKcal: Double?,
        val tdeeKcal: Double?,
    )
}

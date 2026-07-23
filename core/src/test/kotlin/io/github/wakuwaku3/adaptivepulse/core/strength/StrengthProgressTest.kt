package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrengthProgressTest {

    private val gym = Gym(
        id = "gym-1",
        name = "Main Gym",
        trainings = listOf(
            Training(id = "t-press", name = "Chest Press"),
            Training(id = "t-plank", name = "Plank"),
            Training(id = "t-hidden", name = "Old Machine", hidden = true),
            Training(id = "t-empty", name = "New Machine"),
        ),
    )

    private fun workout(
        id: String,
        startedAtMs: Long,
        gymId: String = gym.id,
        entries: List<WorkoutEntry>,
    ) = WorkoutRecord(
        id = id,
        gymId = gymId,
        gymName = "Main Gym",
        startedAtMs = startedAtMs,
        lastInputAtMs = startedAtMs,
        entries = entries,
    )

    private fun entry(trainingId: String, vararg sets: TrainingSet, skipped: Boolean = false) =
        WorkoutEntry(trainingId = trainingId, trainingName = trainingId, skipped = skipped, sets = sets.toList())

    private fun set(weightKg: Double?, reps: Int) = TrainingSet(weightKg = weightKg, reps = reps, recordedAtMs = 0)

    @Test
    fun `epley は weight × (1 + reps30)`() {
        assertEquals(100.0, E1rm.epley(100.0, 0), 1e-9)
        assertEquals(120.0, E1rm.epley(90.0, 10), 1e-9)
    }

    @Test
    fun `負荷あり種目は妥当性範囲内セットの最高 e1RM をセッションベストにする`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout(
                    "w1", 1000,
                    entries = listOf(
                        // 60×10 (e1RM 80) > 62.5×5 (e1RM ≈72.9) — 重い方でなく e1RM が高い方を採る
                        entry("t-press", set(60.0, 10), set(62.5, 5)),
                    ),
                ),
            ),
        )
        val press = progress.single { it.trainingId == "t-press" }
        assertEquals(ProgressMetric.E1RM, press.metric)
        assertEquals(80.0, press.points.single().value, 1e-9)
        assertEquals(false, press.points.single().estimateOnly)
    }

    @Test
    fun `10 reps 超のセットしかない日は参考値として点を打つ`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout("w1", 1000, entries = listOf(entry("t-press", set(40.0, 15)))),
            ),
        )
        val point = progress.single().points.single()
        assertEquals(E1rm.epley(40.0, 15), point.value, 1e-9)
        assertTrue(point.estimateOnly)
    }

    @Test
    fun `10 reps 超と以下が混在する日は範囲内セットだけから算出する`() {
        val progress = trainingProgress(
            gym,
            listOf(
                // 40×20 (e1RM ≈93.3) は範囲外なので、50×8 (e1RM ≈63.3) が勝つ
                workout("w1", 1000, entries = listOf(entry("t-press", set(40.0, 20), set(50.0, 8)))),
            ),
        )
        val point = progress.single().points.single()
        assertEquals(E1rm.epley(50.0, 8), point.value, 1e-9)
        assertEquals(false, point.estimateOnly)
    }

    @Test
    fun `負荷なし種目は最高 reps を系列にする`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout("w1", 1000, entries = listOf(entry("t-plank", set(null, 30), set(null, 45)))),
            ),
        )
        val plank = progress.single { it.trainingId == "t-plank" }
        assertEquals(ProgressMetric.REPS, plank.metric)
        assertEquals(45.0, plank.points.single().value, 1e-9)
    }

    @Test
    fun `一度でも負荷を付けた種目は E1RM 軸になり負荷なしのみの日は点を打たない`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout("w1", 1000, entries = listOf(entry("t-press", set(null, 12)))),
                workout("w2", 2000, entries = listOf(entry("t-press", set(20.0, 10)))),
            ),
        )
        val press = progress.single()
        assertEquals(ProgressMetric.E1RM, press.metric)
        assertEquals(listOf(2000L), press.points.map { it.atMs })
    }

    @Test
    fun `別ジムの workout と skipped エントリは無視し、点は開始時刻の昇順に並ぶ`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout("w2", 2000, entries = listOf(entry("t-press", set(65.0, 10)))),
                workout("w1", 1000, entries = listOf(entry("t-press", set(60.0, 10)))),
                workout("w3", 3000, gymId = "gym-other", entries = listOf(entry("t-press", set(999.0, 10)))),
                workout("w4", 4000, entries = listOf(entry("t-press", skipped = true))),
            ),
        )
        val press = progress.single()
        assertEquals(listOf(1000L, 2000L), press.points.map { it.atMs })
        assertEquals(listOf(E1rm.epley(60.0, 10), E1rm.epley(65.0, 10)), press.points.map { it.value })
    }

    @Test
    fun `hidden と実績なしの種目は返さず、登録順を保つ`() {
        val progress = trainingProgress(
            gym,
            listOf(
                workout(
                    "w1", 1000,
                    entries = listOf(
                        entry("t-plank", set(null, 30)),
                        entry("t-press", set(60.0, 10)),
                        entry("t-hidden", set(50.0, 10)),
                    ),
                ),
            ),
        )
        assertEquals(listOf("t-press", "t-plank"), progress.map { it.trainingId })
    }
}

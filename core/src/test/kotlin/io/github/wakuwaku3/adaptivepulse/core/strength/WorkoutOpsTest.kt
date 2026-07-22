package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutOpsTest {

    private val gym = Gym(id = "gym-1", name = "Main Gym")

    private fun workout(): WorkoutRecord = startWorkout(gym, id = "1000-abc", nowMs = 1000)

    @Test
    fun `startWorkout はジム情報をスナップショットし開始 = 最終入力になる`() {
        val w = workout()
        assertEquals("gym-1", w.gymId)
        assertEquals("Main Gym", w.gymName)
        assertEquals(1000, w.startedAtMs)
        assertEquals(1000, w.lastInputAtMs)
        assertNull(w.endedAtMs)
    }

    @Test
    fun `addSet は entry を作り、2 回目以降は追記して lastInputAtMs を進める`() {
        val w = workout()
            .addSet("t-1", "Chest Press", weightKg = 60.0, reps = 10, nowMs = 2000)
            .addSet("t-1", "Chest Press", weightKg = 60.0, reps = 8, nowMs = 3000)
        val entry = w.entries.single()
        assertEquals(2, entry.sets.size)
        assertEquals(listOf(10, 8), entry.sets.map { it.reps })
        assertEquals(3000, w.lastInputAtMs)
    }

    @Test
    fun `addSet は skipped マークを外す`() {
        val w = workout()
            .setSkipped("t-1", "Chest Press", skipped = true, nowMs = 2000)
            .addSet("t-1", "Chest Press", weightKg = 60.0, reps = 10, nowMs = 3000)
        assertEquals(false, w.entries.single().skipped)
    }

    @Test
    fun `updateSet は値だけ変えて実施時刻 recordedAtMs を保持する`() {
        val w = workout().addSet("t-1", "Chest Press", weightKg = 60.0, reps = 10, nowMs = 2000)
        val updated = w.updateSet("t-1", index = 0, weightKg = 65.0, reps = 9, nowMs = 5000)!!
        val set = updated.entries.single().sets.single()
        assertEquals(65.0, set.weightKg)
        assertEquals(9, set.reps)
        assertEquals(2000, set.recordedAtMs)
        assertEquals(5000, updated.lastInputAtMs)
    }

    @Test
    fun `updateSet と removeSet は存在しない対象に null を返す`() {
        val w = workout().addSet("t-1", "Chest Press", weightKg = 60.0, reps = 10, nowMs = 2000)
        assertNull(w.updateSet("t-x", 0, 60.0, 10, 3000))
        assertNull(w.updateSet("t-1", 1, 60.0, 10, 3000))
        assertNull(w.removeSet("t-1", 1, 3000))
    }

    @Test
    fun `removeSet で最後のセットが消えたら entry ごと未入力に戻る`() {
        val w = workout().addSet("t-1", "Chest Press", weightKg = 60.0, reps = 10, nowMs = 2000)
        assertEquals(emptyList(), w.removeSet("t-1", 0, 3000)!!.entries)
    }

    @Test
    fun `setSkipped の解除はセットが無ければ entry を消す`() {
        val marked = workout().setSkipped("t-1", "Chest Press", skipped = true, nowMs = 2000)
        assertEquals(true, marked.entries.single().skipped)
        assertEquals(emptyList(), marked.setSkipped("t-1", "Chest Press", skipped = false, nowMs = 3000).entries)
    }

    @Test
    fun `finished は endReason = finish で終了する`() {
        val w = workout().finished(nowMs = 9000)
        assertEquals(9000, w.endedAtMs)
        assertEquals(WorkoutEndReason.FINISH, w.endReason)
    }

    @Test
    fun `prefillFor は当日の直前セットを最優先する`() {
        val training = Training(id = "t-1", name = "Chest Press", lastWeightKg = 50.0, lastReps = 12)
        val w = workout().addSet("t-1", "Chest Press", weightKg = 60.0, reps = 8, nowMs = 2000)
        assertEquals(Prefill(weightKg = 60.0, reps = 8), prefillFor(w, "t-1", training))
    }

    @Test
    fun `prefillFor は当日実績が無ければカタログ直近実績、それも無ければ null`() {
        val training = Training(id = "t-1", name = "Chest Press", lastWeightKg = 50.0, lastReps = 12)
        assertEquals(Prefill(weightKg = 50.0, reps = 12), prefillFor(null, "t-1", training))
        assertEquals(Prefill(weightKg = 50.0, reps = 12), prefillFor(workout(), "t-1", training))
        assertNull(prefillFor(null, "t-1", Training(id = "t-1", name = "Chest Press")))
        assertNull(prefillFor(null, "t-1", null))
    }

    @Test
    fun `pendingTrainingCount はセット記録済みと skipped を除いた数`() {
        val visible = listOf(
            Training(id = "t-1", name = "A"),
            Training(id = "t-2", name = "B"),
            Training(id = "t-3", name = "C"),
        )
        assertEquals(3, pendingTrainingCount(null, visible))
        val w = workout()
            .addSet("t-1", "A", weightKg = 60.0, reps = 10, nowMs = 2000)
            .setSkipped("t-2", "B", skipped = true, nowMs = 3000)
        assertEquals(1, pendingTrainingCount(w, visible))
    }
}

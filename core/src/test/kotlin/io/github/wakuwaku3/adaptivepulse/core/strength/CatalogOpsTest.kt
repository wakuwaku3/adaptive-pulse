package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogOpsTest {

    private val empty = StrengthCatalog(updatedAtMs = 0, updatedBy = "test")

    private fun catalogWithGym(vararg trainingNames: String): StrengthCatalog {
        var catalog = empty.addGym("gym-1", "Main Gym")!!
        trainingNames.forEachIndexed { i, name ->
            catalog = catalog.addTraining("gym-1", "t-$i", name)!!
        }
        return catalog
    }

    @Test
    fun `addGym はジムを追加して lastGymId に選択する`() {
        val catalog = empty.addGym("gym-1", " Main Gym ")!!
        assertEquals("Main Gym", catalog.gyms.single().name)
        assertEquals("gym-1", catalog.lastGymId)
    }

    @Test
    fun `addGym は同名 (trim + 大文字小文字無視) と空名を拒否する`() {
        val catalog = empty.addGym("gym-1", "Main Gym")!!
        assertNull(catalog.addGym("gym-2", "main gym "))
        assertNull(catalog.addGym("gym-2", "  "))
        assertNotNull(catalog.addGym("gym-2", "Second Gym"))
    }

    @Test
    fun `renameGym は重複名を拒否し、自分自身への rename は許す`() {
        val catalog = empty.addGym("gym-1", "Main Gym")!!.addGym("gym-2", "Second Gym")!!
        assertNull(catalog.renameGym("gym-2", "MAIN GYM"))
        assertEquals("Main", catalog.renameGym("gym-1", "Main")!!.gyms.first().name)
        // 大文字小文字だけの変更は自分自身とみなして通る
        assertEquals("MAIN GYM", catalog.renameGym("gym-1", "MAIN GYM")!!.gyms.first().name)
    }

    @Test
    fun `selectGym は存在しないジムを拒否する`() {
        val catalog = catalogWithGym()
        assertNull(catalog.selectGym("gym-x"))
        assertEquals("gym-1", catalog.selectGym("gym-1")!!.lastGymId)
    }

    @Test
    fun `addTraining は登録順を保持する`() {
        val catalog = catalogWithGym("Chest Press", "Leg Press", "Lat Pulldown")
        assertEquals(
            listOf("Chest Press", "Leg Press", "Lat Pulldown"),
            catalog.gyms.single().trainings.map { it.name },
        )
    }

    @Test
    fun `addTraining は非表示トレーニングとの重複も拒否する`() {
        val catalog = catalogWithGym("Chest Press").setTrainingHidden("gym-1", "t-0", true)
        assertNull(catalog.addTraining("gym-1", "t-9", " chest press"))
    }

    @Test
    fun `renameTraining は同ジム内の重複を拒否し、別ジムの同名は許す`() {
        var catalog = catalogWithGym("Chest Press", "Leg Press")
        catalog = catalog.addGym("gym-2", "Second Gym")!!
            .addTraining("gym-2", "t-b", "Shoulder Press")!!
        assertNull(catalog.renameTraining("gym-1", "t-1", "CHEST PRESS"))
        // 別ジムに同名があっても gym-1 内で一意なら通る
        val renamed = catalog.renameTraining("gym-1", "t-1", "Shoulder Press")
        assertEquals("Shoulder Press", renamed!!.gyms.first().trainings[1].name)
    }

    @Test
    fun `setTrainingHidden は hide と再表示を切り替える`() {
        val catalog = catalogWithGym("Chest Press")
        val hidden = catalog.setTrainingHidden("gym-1", "t-0", true)
        assertEquals(true, hidden.gyms.single().trainings.single().hidden)
        val shown = hidden.setTrainingHidden("gym-1", "t-0", false)
        assertEquals(false, shown.gyms.single().trainings.single().hidden)
    }

    @Test
    fun `recordSetResult は直近実績を更新する`() {
        val catalog = catalogWithGym("Chest Press")
            .recordSetResult("gym-1", "t-0", weightKg = 60.0, reps = 10, nowMs = 123)
        val training = catalog.gyms.single().trainings.single()
        assertEquals(60.0, training.lastWeightKg)
        assertEquals(10, training.lastReps)
        assertEquals(123, training.lastPerformedAtMs)
    }

    @Test
    fun `recordSetResult は負荷なし (null) も直近実績として上書きする`() {
        val catalog = catalogWithGym("Stretch")
            .recordSetResult("gym-1", "t-0", weightKg = 60.0, reps = 10, nowMs = 1)
            .recordSetResult("gym-1", "t-0", weightKg = null, reps = 15, nowMs = 2)
        val training = catalog.gyms.single().trainings.single()
        assertNull(training.lastWeightKg)
        assertEquals(15, training.lastReps)
    }
}

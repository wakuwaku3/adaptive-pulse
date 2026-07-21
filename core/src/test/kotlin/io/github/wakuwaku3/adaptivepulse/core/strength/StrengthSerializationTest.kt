package io.github.wakuwaku3.adaptivepulse.core.strength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class StrengthSerializationTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun `StrengthCatalog は JSON を往復しても等価`() {
        val catalog = StrengthCatalog(
            gyms = listOf(
                Gym(
                    id = "gym-1",
                    name = "Main Gym",
                    trainings = listOf(
                        Training(
                            id = "t-1",
                            name = "Chest Press",
                            lastWeightKg = 60.0,
                            lastReps = 10,
                            lastPerformedAtMs = 123,
                        ),
                        Training(id = "t-2", name = "Stretch", hidden = true),
                    ),
                ),
            ),
            lastGymId = "gym-1",
            updatedAtMs = 456,
            updatedBy = "phone",
        )
        val json = Json.encodeToString(StrengthCatalog.serializer(), catalog)
        assertEquals(catalog, Json.decodeFromString(StrengthCatalog.serializer(), json))
    }

    @Test
    fun `WorkoutRecord は JSON を往復しても等価`() {
        val record = WorkoutRecord(
            id = "1718064000000-abc",
            gymId = "gym-1",
            gymName = "Main Gym",
            startedAtMs = 1_718_064_000_000,
            lastInputAtMs = 1_718_065_000_000,
            endedAtMs = 1_718_065_000_000,
            endReason = WorkoutEndReason.CARDIO,
            entries = listOf(
                WorkoutEntry(
                    trainingId = "t-1",
                    trainingName = "Chest Press",
                    sets = listOf(
                        TrainingSet(weightKg = 60.0, reps = 10, recordedAtMs = 1),
                        TrainingSet(weightKg = null, reps = 15, recordedAtMs = 2),
                    ),
                ),
                WorkoutEntry(trainingId = "t-2", trainingName = "Leg Press", skipped = true),
            ),
        )
        val json = Json.encodeToString(WorkoutRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(WorkoutRecord.serializer(), json))
    }

    @Test
    fun `schema は 1 で、未知フィールドを含む将来の JSON も読める`() {
        val record = WorkoutRecord(id = "w", gymId = "g", gymName = "G", startedAtMs = 1, lastInputAtMs = 1)
        assertEquals(1, record.schema)
        assertEquals(1, StrengthCatalog(updatedAtMs = 0, updatedBy = "phone").schema)

        val future = """{"id":"w","schema":2,"gymId":"g","gymName":"G","startedAtMs":1,
            "lastInputAtMs":1,"futureField":[1,2,3]}"""
        val decoded = lenient.decodeFromString(WorkoutRecord.serializer(), future)
        assertEquals(2, decoded.schema)
        assertEquals(emptyList(), decoded.entries)
    }
}

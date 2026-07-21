package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DailyHealthRecordTest {

    @Test
    fun `DailyHealthRecord は JSON を往復しても等価`() {
        val record = DailyHealthRecord(
            date = "2026-06-18",
            restingHeartRateBpm = 58,
            hrvRmssdMs = 42.5,
            avgHeartRateBpm = 72,
            sleepDurationMin = 410,
            sleepDeepMin = 85,
            sleepRemMin = 120,
            sleepLightMin = 180,
            sleepAwakeMin = 25,
            steps = 8243,
            activeCaloriesKcal = 412.3,
            totalCaloriesKcal = 2380.1,
            weightKg = 88.4,
            bodyFatPct = 22.1,
            leanBodyMassKg = 68.8,
            heightCm = 175.0,
            intakeKcal = 1850.0,
            proteinG = 142.0,
            fatG = 55.0,
            carbsG = 180.0,
        )
        val json = Json.encodeToString(DailyHealthRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(DailyHealthRecord.serializer(), json))
    }

    @Test
    fun `欠損フィールドは null のまま round-trip する (推定で埋めない)`() {
        // 体重計や食事ログが未連携で何も書かれていない日 = ほぼ全部 null になるケース
        val record = DailyHealthRecord(date = "2026-06-18", steps = 1234L)
        val json = Json.encodeToString(DailyHealthRecord.serializer(), record)
        val decoded = Json.decodeFromString(DailyHealthRecord.serializer(), json)
        assertEquals(record, decoded)
        assertEquals(null, decoded.weightKg)
        assertEquals(null, decoded.intakeKcal)
    }

    @Test
    fun `isEmpty は date 以外が全 null のときだけ true`() {
        // HC が一時的に何も返さなかった日 (Firestore 書き込みを skip すべきケース)
        assertTrue(DailyHealthRecord(date = "2026-06-18").isEmpty)
        // どこか 1 つでも値が入っていれば書き込む
        assertFalse(DailyHealthRecord(date = "2026-06-18", steps = 0L).isEmpty)
        assertFalse(DailyHealthRecord(date = "2026-06-18", sleepDurationMin = 1L).isEmpty)
        assertFalse(DailyHealthRecord(date = "2026-06-18", weightKg = 90.0).isEmpty)
    }

    @Test
    fun `breakdown と externalSessions を含めて round-trip する`() {
        val record = DailyHealthRecord(
            date = "2026-07-21",
            steps = 9000,
            breakdown = listOf(
                MetricSourceValue("steps", "com.google.android.apps.fitness", 8800.0),
                MetricSourceValue("steps", "com.fitbit.FitbitMobile", 9000.0),
            ),
            externalSessions = listOf(
                ExternalExerciseSession(
                    id = "hc-1",
                    startTimeMs = 1_750_000_000_000,
                    endTimeMs = 1_750_001_800_000,
                    exerciseType = 56,
                    title = "Morning Run",
                    sourcePackage = "com.fitbit.FitbitMobile",
                ),
            ),
        )
        val json = Json.encodeToString(DailyHealthRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(DailyHealthRecord.serializer(), json))
    }

    @Test
    fun `breakdown だけ入った行も isEmpty ではない`() {
        val record = DailyHealthRecord(
            date = "2026-07-21",
            breakdown = listOf(MetricSourceValue("steps", "pkg", 1.0)),
        )
        assertFalse(record.isEmpty)
    }

    @Test
    fun `旧版が書いた breakdown 無し JSON も読める (後方互換)`() {
        val json = """{"date": "2026-07-20", "steps": 100}"""
        val lenient = Json { ignoreUnknownKeys = true }
        val decoded = lenient.decodeFromString(DailyHealthRecord.serializer(), json)
        assertEquals(null, decoded.breakdown)
        assertEquals(null, decoded.externalSessions)
    }

    @Test
    fun `hasMeasuredData は HC 合成カロリーと派生値だけの行を実測なしと判定する`() {
        // 履歴権限なしの時代に HC が BMR 由来で合成した行 (2026-07-21 の体重欠落の原因形)
        val syntheticOnly = DailyHealthRecord(
            date = "2025-08-15",
            totalCaloriesKcal = 1607.4,
            basalCaloriesKcal = 1607.4,
            tdeeKcal = 1607.4,
            exerciseExtraKcal = 0.0,
            breakdown = listOf(MetricSourceValue("basalKcal", "pkg", 1607.4)),
        )
        assertFalse(syntheticOnly.hasMeasuredData)
        assertFalse(syntheticOnly.isEmpty) // 書き込みガードとしては空扱いしない (既存挙動を保つ)

        // 実測が 1 つでもあれば確定済み
        assertTrue(DailyHealthRecord(date = "2025-08-15", weightKg = 88.0).hasMeasuredData)
        assertTrue(DailyHealthRecord(date = "2025-08-15", steps = 100).hasMeasuredData)
        assertTrue(
            DailyHealthRecord(
                date = "2025-08-15",
                totalCaloriesKcal = 2000.0,
                sleepDurationMin = 400,
            ).hasMeasuredData,
        )

        // 完全な空行は当然 false
        assertFalse(DailyHealthRecord(date = "2025-08-15").hasMeasuredData)
    }

    @Test
    fun `HealthDataExport は dailyMetrics と sessions を含めて round-trip する`() {
        val export = HealthDataExport(
            exportedAtMs = 1_750_000_000_000,
            fromDate = "2026-05-20",
            toDate = "2026-06-18",
            dailyMetrics = listOf(
                DailyHealthRecord(date = "2026-06-17", restingHeartRateBpm = 60),
                DailyHealthRecord(date = "2026-06-18", restingHeartRateBpm = 58),
            ),
            sessions = listOf(
                SessionRecord(
                    id = "1718064000000-abc",
                    startedAtMs = 1_718_064_000_000,
                    durationSec = 780,
                    cycles = 7,
                    plannedCycles = 7,
                    fatigueBrake = false,
                    calories = 95.0,
                    config = SessionConfigSnapshot.from(SessionConfig()),
                ),
            ),
        )
        val json = Json.encodeToString(HealthDataExport.serializer(), export)
        assertEquals(export, Json.decodeFromString(HealthDataExport.serializer(), json))
    }

    @Test
    fun `古い export に未知フィールドが足されても新クライアントで読める`() {
        // 将来 schema 拡張に備えた hedge。Json { ignoreUnknownKeys = true } で復元する
        val json = """{
            "schema": 1,
            "exportedAtMs": 100,
            "fromDate": "2026-06-01",
            "toDate": "2026-06-18",
            "dailyMetrics": [],
            "sessions": [],
            "futureField": "hello"
        }"""
        val lenient = Json { ignoreUnknownKeys = true }
        val decoded = lenient.decodeFromString(HealthDataExport.serializer(), json)
        assertEquals("2026-06-18", decoded.toDate)
    }
}

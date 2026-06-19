package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
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

package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class SessionRecordTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun `SessionRecord は JSON を往復しても等価`() {
        val record = SessionRecord(
            id = "1718064000000-abc",
            startedAtMs = 1_718_064_000_000,
            durationSec = 134,
            cycles = 7,
            plannedCycles = 7,
            fatigueBrake = false,
            calories = 39.2,
            zoneRatio = 0.41,
            highDurationsSec = listOf(21.0, 18.5, 17.0),
            avgBpm = 132,
            maxBpm = 158,
            config = SessionConfigSnapshot.from(SessionConfig()),
        )
        val json = Json.encodeToString(SessionRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(SessionRecord.serializer(), json))
    }

    @Test
    fun `SessionRecord schema は 5 (セッション中 HR 列の追加で bump)`() {
        val record = SessionRecord(
            id = "x",
            startedAtMs = 0,
            durationSec = 0,
            cycles = 0,
            plannedCycles = 0,
            fatigueBrake = false,
            config = SessionConfigSnapshot.from(SessionConfig()),
        )
        assertEquals(5, record.schema)
        // 旧クライアントが書いた schema=2 + lockedCadenceTier / 旧 final target 等を含む JSON も読める
        val configJson = Json.encodeToString(SessionConfigSnapshot.serializer(), record.config)
        val legacy = """{"id":"y","schema":2,"startedAtMs":1,"durationSec":1,"cycles":1,
            "plannedCycles":1,"fatigueBrake":false,"config":$configJson,
            "finalTargetCadenceHigh":135.0,"finalTargetCadenceRecovery":70.0,
            "lockedCadenceTier":"STEPS_PER_MINUTE"}"""
        val decoded = lenient.decodeFromString(SessionRecord.serializer(), legacy)
        assertEquals(2, decoded.schema)
    }

    @Test
    fun `plan 付き (schema 4) の JSON 往復と、plan 無し旧 JSON の読み込み`() {
        val record = SessionRecord(
            id = "p",
            startedAtMs = 1,
            durationSec = 1800,
            cycles = 6,
            plannedCycles = 6,
            fatigueBrake = false,
            config = SessionConfigSnapshot.from(SessionConfig()),
            plan = SessionPlanSnapshot(
                programId = "preset-standard",
                name = "Standard",
                segments = listOf(
                    SegmentSnapshot(
                        menuId = "preset-warmup",
                        menuName = "Warm-up",
                        type = "timed",
                        upperBpm = 116,
                        lowerBpm = null,
                        plannedAmount = 5,
                        elapsedSec = 300.0,
                    ),
                    SegmentSnapshot(
                        menuId = "hiit",
                        menuName = "hiit",
                        type = "interval",
                        upperBpm = 159,
                        lowerBpm = 136,
                        plannedAmount = 6,
                        completedCycles = 6,
                        elapsedSec = 900.0,
                        highDurationsSec = listOf(60.0),
                        recoveryDurationsSec = listOf(85.0),
                    ),
                ),
            ),
        )
        val json = Json.encodeToString(SessionRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(SessionRecord.serializer(), json))

        // plan フィールドを持たない旧レコードは null で読める
        val configJson = Json.encodeToString(SessionConfigSnapshot.serializer(), record.config)
        val legacy = """{"id":"z","schema":3,"startedAtMs":1,"durationSec":1,"cycles":1,
            "plannedCycles":1,"fatigueBrake":false,"config":$configJson}"""
        assertEquals(null, lenient.decodeFromString(SessionRecord.serializer(), legacy).plan)
    }

    @Test
    fun `hrBpmBySecond 付き (schema 5) の JSON 往復と、HR 列無し旧 JSON の読み込み`() {
        // 合成データ: warm-up 中の欠落 (null) を含む 1 秒グリッド
        val record = SessionRecord(
            id = "h",
            startedAtMs = 1,
            durationSec = 6,
            cycles = 1,
            plannedCycles = 1,
            fatigueBrake = false,
            hrBpmBySecond = listOf(null, null, 95, 102, 110, 118, 125),
            config = SessionConfigSnapshot.from(SessionConfig()),
        )
        val json = Json.encodeToString(SessionRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(SessionRecord.serializer(), json))

        val configJson = Json.encodeToString(SessionConfigSnapshot.serializer(), record.config)
        val legacy = """{"id":"z","schema":4,"startedAtMs":1,"durationSec":1,"cycles":1,
            "plannedCycles":1,"fatigueBrake":false,"config":$configJson}"""
        assertEquals(null, lenient.decodeFromString(SessionRecord.serializer(), legacy).hrBpmBySecond)
    }

    @Test
    fun `SettingsDocument は SessionConfig と相互変換できる`() {
        val config = SessionConfig(upperBpm = 150, lowerBpm = 120)
        val doc = SettingsDocument.from(config, updatedAtMs = 123, updatedBy = "watch")
        assertEquals(config, doc.toSessionConfig())
        assertEquals(123, doc.updatedAtMs)
    }

    @Test
    fun `未知フィールドを無視して古いクライアントでも読める`() {
        val json = """{"upperBpm":155,"lowerBpm":140,"targetCycles":7,"fatigueRatio":0.5,
            "minBaselineSec":45,"highTimeoutSec":240,"recoveryTimeoutSec":180,
            "updatedAtMs":1,"updatedBy":"phone","futureField":true}"""
        assertEquals(155, lenient.decodeFromString(SettingsDocument.serializer(), json).upperBpm)
    }
}

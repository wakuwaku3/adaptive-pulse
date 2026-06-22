package io.github.wakuwaku3.adaptivepulse.core.sync

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.cadence.CadenceTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
            lockedCadenceTier = CadenceTier.STEPS_PER_MINUTE,
        )
        val json = Json.encodeToString(SessionRecord.serializer(), record)
        assertEquals(record, Json.decodeFromString(SessionRecord.serializer(), json))
    }

    @Test
    fun `SessionRecord schema は 2 (lockedCadenceTier 追加で bump)`() {
        val record = SessionRecord(
            id = "x",
            startedAtMs = 0,
            durationSec = 0,
            cycles = 0,
            plannedCycles = 0,
            fatigueBrake = false,
            config = SessionConfigSnapshot.from(SessionConfig()),
        )
        assertEquals(2, record.schema)
        // 旧クライアントが書いた schema=1 + lockedCadenceTier 無し JSON も読める
        val configJson = Json.encodeToString(SessionConfigSnapshot.serializer(), record.config)
        val legacy = """{"id":"y","schema":1,"startedAtMs":1,"durationSec":1,"cycles":1,
            "plannedCycles":1,"fatigueBrake":false,"config":$configJson}"""
        val decoded = lenient.decodeFromString(SessionRecord.serializer(), legacy)
        assertEquals(1, decoded.schema)
        assertNull(decoded.lockedCadenceTier)
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

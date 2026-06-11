package io.github.wakuwaku3.adaptivepulse.hr

import kotlinx.coroutines.flow.Flow

/**
 * 心拍ソースの抽象。Health Services / BLE (Polar H10) / 合成データを
 * 同一 interface の実装として扱い、ロジック側からは区別しない (.claude/rules/kotlin.md)。
 */
fun interface HeartRateSource {
    fun heartRates(): Flow<Int>
}

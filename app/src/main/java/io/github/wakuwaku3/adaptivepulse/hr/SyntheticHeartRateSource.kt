package io.github.wakuwaku3.adaptivepulse.hr

import io.github.wakuwaku3.adaptivepulse.core.Phase
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ロジック検証用の合成心拍ソース。フェーズ通知に従ってペースを変える人間を模し、
 * 高強度なら上限閾値を超えるまで上昇、回復なら下限閾値を下回るまで下降する。
 * エミュレータ/実機センサー無しでステートマシン全体を回すために使う。
 */
class SyntheticHeartRateSource(
    private val phaseProvider: () -> Phase,
) : HeartRateSource {

    override fun heartRates(): Flow<Int> = flow {
        var bpm = 115.0
        while (true) {
            val target = when (phaseProvider()) {
                Phase.HIGH_INTENSITY -> 170.0
                Phase.RECOVERY -> 120.0
                Phase.FINISHED -> 100.0
            }
            // 目標心拍へ指数的に漸近 (120→155 が 30〜40 秒程度) + センサーノイズ
            bpm += (target - bpm) * 0.05 + Random.nextDouble(-1.5, 1.5)
            emit(bpm.roundToInt())
            delay(1.seconds)
        }
    }
}

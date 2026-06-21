package io.github.wakuwaku3.adaptivepulse.hr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.wakuwaku3.adaptivepulse.core.cadence.AccelerometerCadenceEstimator
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 加速度センサーの peak detection から SPM を推定する tier 3 ソース。
 * Health Services の歩行検出が反応しないクロストレーナーでも、腕振り由来の
 * 周期がそのまま出るため SPM 代替に使える (精度は tier 1/2 より低い)。
 *
 * 1 秒ごとに現在の窓推定を吐く。値が固まらないとき (静止・ノイズ未満) は null。
 */
class AccelerometerCadenceSource(
    private val context: Context,
    private val emitInterval: Duration = 1.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    fun spm(): Flow<Double?> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        // 重力除去後の振動を見る。TYPE_LINEAR_ACCELERATION が無い古い端末では諦める
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val mark = timeSource.markNow()
        val estimator = AccelerometerCadenceEstimator()
        // 1Hz で値を流すために最終 emit 時刻を抱えておく
        var lastEmitAt: Duration = Duration.ZERO

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val now = mark.elapsedNow()
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt((x * x + y * y + z * z).toDouble())
                estimator.add(now, magnitude)
                if (now - lastEmitAt >= emitInterval) {
                    lastEmitAt = now
                    trySend(estimator.spm(now))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // 50Hz (20ms) で十分。SENSOR_DELAY_GAME = ~20ms
        manager.registerListener(listener, sensor, 20_000)
        awaitClose { manager.unregisterListener(listener) }
    }

    companion object {
        // freshness 判定の参考用 (decorator が共通で使う)
        val DefaultStaleThreshold: Duration = 3.seconds
    }
}

package io.github.wakuwaku3.adaptivepulse.hr

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.guava.await

/**
 * Health Services ExerciseClient による心拍ソース。セッションを OS にワークアウト
 * として認識させ、画面オフ中のセンサー継続取得や将来の Health Connect 連携の
 * 土台になる (docs/stock/tech.md)。BODY_SENSORS 許可済みであることが前提。
 */
class ExerciseClientHeartRateSource(
    context: Context,
    private val exerciseType: ExerciseType = ExerciseType.ELLIPTICAL,
) : HeartRateSource {

    private val client = HealthServices.getClient(context).exerciseClient

    override fun heartRates(): Flow<Int> = callbackFlow {
        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                update.latestMetrics.getData(DataType.HEART_RATE_BPM).forEach { sample ->
                    trySend(sample.value.roundToInt())
                }
            }

            override fun onAvailabilityChanged(
                dataType: DataType<*, *>,
                availability: Availability,
            ) = Unit

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onRegistered() = Unit

            override fun onRegistrationFailed(throwable: Throwable) {
                close(throwable)
            }
        }

        client.setUpdateCallback(callback)
        client.startExerciseAsync(
            ExerciseConfig.builder(exerciseType)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .build(),
        ).await()

        awaitClose {
            // collect の終了 = セッション終了。ワークアウトも閉じる
            client.clearUpdateCallbackAsync(callback)
            client.endExerciseAsync()
        }
    }
}

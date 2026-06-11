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
 * Health Services ExerciseClient によるデータソース。セッションを OS にワークアウト
 * として認識させ、画面オフ中のセンサー継続取得や将来の Health Connect 連携の
 * 土台になる (docs/stock/tech.md)。BODY_SENSORS 許可済みであることが前提。
 * カロリー累計は能力があるときだけ要求し、サンプルに載せる。
 */
class HealthServicesExerciseSource(
    private val context: Context,
    private val exerciseType: ExerciseType = ExerciseType.ELLIPTICAL,
) : ExerciseSource {

    private val client = HealthServices.getClient(context).exerciseClient

    override fun samples(): Flow<ExerciseSample> = callbackFlow {
        var totalCalories: Double? = null

        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                update.latestMetrics.getData(DataType.CALORIES_TOTAL)?.let {
                    totalCalories = it.total
                }
                update.latestMetrics.getData(DataType.HEART_RATE_BPM).forEach { sample ->
                    trySend(
                        ExerciseSample(
                            bpm = sample.value.roundToInt(),
                            totalCalories = totalCalories,
                        ),
                    )
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

        val supported = client.getCapabilitiesAsync().await()
            .getExerciseTypeCapabilities(exerciseType).supportedDataTypes
        // カロリーは ACTIVITY_RECOGNITION が要る。未許可で要求すると
        // startExercise が SecurityException になるため、許可済みのときだけ足す
        val canReadCalories = context.checkSelfPermission(
            android.Manifest.permission.ACTIVITY_RECOGNITION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val dataTypes = buildSet {
            add(DataType.HEART_RATE_BPM)
            if (canReadCalories && DataType.CALORIES_TOTAL in supported) {
                add(DataType.CALORIES_TOTAL)
            }
        }

        client.setUpdateCallback(callback)
        client.startExerciseAsync(
            ExerciseConfig.builder(exerciseType)
                .setDataTypes(dataTypes)
                .build(),
        ).await()

        awaitClose {
            // collect の終了 = セッション終了。ワークアウトも閉じる
            client.clearUpdateCallbackAsync(callback)
            client.endExerciseAsync()
        }
    }
}

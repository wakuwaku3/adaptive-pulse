package io.github.wakuwaku3.adaptivepulse.hr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import io.github.wakuwaku3.adaptivepulse.core.Phase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await

private const val TAG = "AdaptivePulse"

/**
 * collect 時に実センサー経路 (Health Services) が使えるか判定し、
 * 使えなければ合成ソースに切り替える。判定は能力照会ベースなので、
 * エミュレータ (WHS の合成センサー) でも実機でも同じ経路を通る。
 */
class AutoExerciseSource(
    private val context: Context,
    private val phaseProvider: () -> Phase,
) : ExerciseSource {

    override fun samples(): Flow<ExerciseSample> = flow {
        emitAll(selectSource().samples())
    }

    private suspend fun selectSource(): ExerciseSource {
        if (context.checkSelfPermission(Manifest.permission.BODY_SENSORS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "BODY_SENSORS 未許可のため合成データソースを使用")
            return SyntheticExerciseSource(phaseProvider)
        }
        val exerciseType = runCatching { supportedExerciseType() }.getOrElse {
            Log.w(TAG, "Health Services の能力照会に失敗。合成データソースを使用", it)
            null
        }
        if (exerciseType == null) {
            Log.i(TAG, "心拍対応のワークアウト種別が無いため合成データソースを使用")
            return SyntheticExerciseSource(phaseProvider)
        }
        Log.i(TAG, "ExerciseClient ($exerciseType) で計測")
        // tier 1/2 で SPM が出ないクロストレーナーでも、加速度 peak で穴埋めする
        return HealthServicesExerciseSource(
            context = context,
            exerciseType = exerciseType,
            accelerometerSpm = AccelerometerCadenceSource(context).spm(),
        )
    }

    /** クロスストレーナー (ELLIPTICAL) を優先し、無ければ汎用 WORKOUT で心拍対応を探す */
    private suspend fun supportedExerciseType(): ExerciseType? {
        val capabilities =
            HealthServices.getClient(context).exerciseClient.getCapabilitiesAsync().await()
        return listOf(ExerciseType.ELLIPTICAL, ExerciseType.WORKOUT).firstOrNull { type ->
            type in capabilities.supportedExerciseTypes &&
                DataType.HEART_RATE_BPM in
                capabilities.getExerciseTypeCapabilities(type).supportedDataTypes
        }
    }
}

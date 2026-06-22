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
import io.github.wakuwaku3.adaptivepulse.core.SessionPhaseSnapshot
import io.github.wakuwaku3.adaptivepulse.core.cadence.CadenceTier
import io.github.wakuwaku3.adaptivepulse.core.cadence.StepsDeltaCadenceEstimator
import io.github.wakuwaku3.adaptivepulse.core.cadence.TieredCadenceLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.await

/**
 * Health Services ExerciseClient によるデータソース。セッションを OS にワークアウト
 * として認識させ、画面オフ中のセンサー継続取得や将来の Health Connect 連携の
 * 土台になる (docs/stock/tech.md)。BODY_SENSORS 許可済みであることが前提。
 *
 * cadence (SPM) は 3 段で取り、**warm-up を抜けた瞬間 (= 実走開始)** から discovery 窓
 * (30s) を回して最良 tier を確定する:
 *   tier 1: `DataType.STEPS_PER_MINUTE` (watch の歩行検出が出す瞬時 rate)
 *   tier 2: `DataType.STEPS_TOTAL` の差分から再構成した SPM
 *   tier 3: 注入された加速度由来の SPM Flow (歩行検出が効かない動きの保険)
 * 確定後はセッション中ずっとその tier だけを使う ([TieredCadenceLock])。
 */
class HealthServicesExerciseSource(
    private val context: Context,
    private val sessionPhase: () -> SessionPhaseSnapshot,
    private val exerciseType: ExerciseType = ExerciseType.ELLIPTICAL,
    private val accelerometerSpm: Flow<Double?> = emptyFlow(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ExerciseSource {

    private val client = HealthServices.getClient(context).exerciseClient

    override fun samples(): Flow<ExerciseSample> = callbackFlow {
        val mark = timeSource.markNow()
        val stepsDelta = StepsDeltaCadenceEstimator()
        val lock = TieredCadenceLock()

        // 各 tier の最新観測値 (lock 後の emit に使う). callback と launchIn(this) の両方から
        // 書き込まれるので AtomicReference で memory visibility を確保
        val tier1Value = AtomicReference<Double?>(null)
        val tier3Value = AtomicReference<Double?>(null)

        var totalCalories: Double? = null

        accelerometerSpm
            .onEach { value ->
                if (value != null) {
                    tier3Value.set(value)
                    lock.observe(CadenceTier.ACCELEROMETER, mark.elapsedNow())
                }
            }
            .launchIn(this)

        val callback = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                val now = mark.elapsedNow()
                // warm-up を抜けた瞬間に discovery 窓を起動する (= 実走開始トリガ)
                if (!sessionPhase().isWarmingUp) lock.startDiscovery(now)
                update.latestMetrics.getData(DataType.CALORIES_TOTAL)?.let {
                    totalCalories = it.total
                }
                update.latestMetrics.getData(DataType.STEPS_PER_MINUTE).lastOrNull()?.let {
                    tier1Value.set(it.value.toDouble())
                    lock.observe(CadenceTier.STEPS_PER_MINUTE, now)
                }
                update.latestMetrics.getData(DataType.STEPS_TOTAL)?.let {
                    if (stepsDelta.update(now, it.total) != null) {
                        lock.observe(CadenceTier.STEPS_TOTAL_DELTA, now)
                    }
                }
                update.latestMetrics.getData(DataType.HEART_RATE_BPM).forEach { sample ->
                    val emitNow = mark.elapsedNow()
                    val tier = lock.currentTier(emitNow)
                    val spm = pickSpm(tier, stepsDelta, tier1Value, tier3Value, emitNow)
                    trySend(
                        ExerciseSample(
                            bpm = sample.value.roundToInt(),
                            totalCalories = totalCalories,
                            stepsPerMinute = spm,
                            cadenceTier = lock.lockedTier ?: tier,
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
            if (DataType.STEPS_PER_MINUTE in supported) {
                add(DataType.STEPS_PER_MINUTE)
            }
            if (DataType.STEPS_TOTAL in supported) {
                add(DataType.STEPS_TOTAL)
            }
        }

        client.setUpdateCallback(callback)
        client.startExerciseAsync(
            ExerciseConfig.builder(exerciseType)
                .setDataTypes(dataTypes)
                .build(),
        ).await()

        awaitClose {
            client.clearUpdateCallbackAsync(callback)
            client.endExerciseAsync()
        }
    }

    private fun pickSpm(
        tier: CadenceTier?,
        stepsDelta: StepsDeltaCadenceEstimator,
        tier1: AtomicReference<Double?>,
        tier3: AtomicReference<Double?>,
        now: Duration,
    ): Double? = when (tier) {
        CadenceTier.STEPS_PER_MINUTE -> tier1.get()
        CadenceTier.STEPS_TOTAL_DELTA -> stepsDelta.spm(now)
        CadenceTier.ACCELEROMETER -> tier3.get()
        null -> null
    }
}

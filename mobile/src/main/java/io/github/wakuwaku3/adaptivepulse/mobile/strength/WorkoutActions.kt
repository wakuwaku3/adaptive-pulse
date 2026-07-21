package io.github.wakuwaku3.adaptivepulse.mobile.strength

import android.content.Context
import android.util.Log
import io.github.wakuwaku3.adaptivepulse.core.strength.Gym
import io.github.wakuwaku3.adaptivepulse.core.strength.StrengthCatalog
import io.github.wakuwaku3.adaptivepulse.core.strength.Training
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutAutoEnd
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutEndReason
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import io.github.wakuwaku3.adaptivepulse.core.strength.addGym
import io.github.wakuwaku3.adaptivepulse.core.strength.addSet
import io.github.wakuwaku3.adaptivepulse.core.strength.addTraining
import io.github.wakuwaku3.adaptivepulse.core.strength.finished
import io.github.wakuwaku3.adaptivepulse.core.strength.recordSetResult
import io.github.wakuwaku3.adaptivepulse.core.strength.removeSet
import io.github.wakuwaku3.adaptivepulse.core.strength.renameGym
import io.github.wakuwaku3.adaptivepulse.core.strength.renameTraining
import io.github.wakuwaku3.adaptivepulse.core.strength.selectGym
import io.github.wakuwaku3.adaptivepulse.core.strength.setSkipped
import io.github.wakuwaku3.adaptivepulse.core.strength.setTrainingHidden
import io.github.wakuwaku3.adaptivepulse.core.strength.startWorkout
import io.github.wakuwaku3.adaptivepulse.core.strength.updateSet
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AdaptivePulse"

/**
 * 筋トレ記録画面の操作。各操作は「core 純関数 → ローカル保存 → Firestore へ
 * fire-and-forget upload」の順で行い、通信失敗はローカルの dirty マーカーが吸収する。
 * 名前系操作の Boolean は false = 一意性違反 (UI がインラインエラーを出す)。
 */
class WorkoutActions(private val context: Context) {

    private val catalogRepo = StrengthCatalogRepository(context)
    private val store = WorkoutStore(context)

    val catalog: Flow<StrengthCatalog?> = catalogRepo.stored

    private val active = MutableStateFlow<WorkoutRecord?>(null)
    val activeWorkout: StateFlow<WorkoutRecord?> = active

    /** 画面 open 時: 進行中 workout の再開 or 自動終了の確定、未送信分の再送 */
    suspend fun openScreen() {
        val latest = store.latest()
        active.value = when {
            latest == null || latest.endedAtMs != null -> null
            else -> {
                val ended = WorkoutAutoEnd.evaluate(latest, System.currentTimeMillis())
                if (ended != null) {
                    persist(ended)
                    Log.i(TAG, "タイムアウトにより workout を自動終了: ${ended.id}")
                    null
                } else {
                    latest
                }
            }
        }
        store.dirty().forEach { if (FirestoreSync.uploadWorkout(it)) store.markUploaded(it.id) }
        // カタログは 1 doc だけなので毎回 put し直す (オフライン中の変更の再送を兼ねる)
        catalogRepo.load().takeIf { it.updatedAtMs > 0 }?.let { FirestoreSync.putStrengthCatalog(it) }
    }

    suspend fun addGym(name: String): Boolean =
        updateCatalog { it.addGym(newId("gym"), name) }

    suspend fun renameGym(gymId: String, name: String): Boolean =
        updateCatalog { it.renameGym(gymId, name) }

    suspend fun selectGym(gymId: String) {
        updateCatalog { it.selectGym(gymId) }
    }

    suspend fun addTraining(gymId: String, name: String): Boolean =
        updateCatalog { it.addTraining(gymId, newId("training"), name) }

    suspend fun renameTraining(gymId: String, trainingId: String, name: String): Boolean =
        updateCatalog { it.renameTraining(gymId, trainingId, name) }

    suspend fun setTrainingHidden(gymId: String, trainingId: String, hidden: Boolean) {
        updateCatalog { it.setTrainingHidden(gymId, trainingId, hidden) }
    }

    suspend fun addSet(gym: Gym, training: Training, weightKg: Double?, reps: Int) {
        val now = System.currentTimeMillis()
        val current = active.value?.let { workout ->
            // 進行中のまま別ジムで入力し始めたら、前の workout は終了扱いにして分ける
            if (workout.gymId != gym.id) {
                persist(workout.copy(endedAtMs = workout.lastInputAtMs, endReason = WorkoutEndReason.FINISH))
                null
            } else {
                workout
            }
        } ?: startWorkout(gym, id = "$now-${UUID.randomUUID().toString().take(8)}", nowMs = now)
        val updated = current.addSet(training.id, training.name, weightKg, reps, now)
        active.value = updated
        persist(updated)
        updateCatalog { it.recordSetResult(gym.id, training.id, weightKg, reps, now) }
    }

    suspend fun updateSet(trainingId: String, index: Int, weightKg: Double?, reps: Int) {
        val updated = active.value?.updateSet(trainingId, index, weightKg, reps, System.currentTimeMillis()) ?: return
        active.value = updated
        persist(updated)
    }

    suspend fun removeSet(trainingId: String, index: Int) {
        val updated = active.value?.removeSet(trainingId, index, System.currentTimeMillis()) ?: return
        active.value = updated
        persist(updated)
    }

    suspend fun setSkipped(gym: Gym, training: Training, skipped: Boolean) {
        val now = System.currentTimeMillis()
        val current = active.value?.takeIf { it.gymId == gym.id }
            ?: startWorkout(gym, id = "$now-${UUID.randomUUID().toString().take(8)}", nowMs = now)
        val updated = current.setSkipped(training.id, training.name, skipped, now)
        active.value = updated
        persist(updated)
    }

    suspend fun finish() {
        val ended = active.value?.finished(System.currentTimeMillis()) ?: return
        active.value = null
        persist(ended)
    }

    private suspend fun persist(record: WorkoutRecord) {
        store.save(record)
        if (FirestoreSync.uploadWorkout(record)) store.markUploaded(record.id)
    }

    private suspend fun updateCatalog(transform: (StrengthCatalog) -> StrengthCatalog?): Boolean {
        val updated = catalogRepo.update(transform) ?: return false
        FirestoreSync.putStrengthCatalog(updated)
        return true
    }

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}"
}

/**
 * 有酸素セッション開始による自動終了の入口。PhoneSyncService (Activity 非依存) から
 * 呼ばれるため、画面の WorkoutActions とは状態を共有せずファイルストアを直接見る
 * (画面側は次の openScreen で終了済みを拾う)。
 */
object StrengthAutoEnder {

    suspend fun onCardioStarted(context: Context, cardioStartedAtMs: Long) {
        val store = WorkoutStore(context)
        val latest = store.latest() ?: return
        val ended = WorkoutAutoEnd.evaluate(latest, System.currentTimeMillis(), cardioStartedAtMs) ?: return
        store.save(ended)
        if (FirestoreSync.uploadWorkout(ended)) store.markUploaded(ended.id)
        Log.i(TAG, "有酸素開始により workout を自動終了: ${ended.id} (${ended.endReason})")
    }
}

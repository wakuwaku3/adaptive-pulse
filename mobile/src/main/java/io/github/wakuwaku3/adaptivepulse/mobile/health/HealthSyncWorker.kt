package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PhoneSync
import java.time.LocalDate

private const val TAG = "AdaptivePulse"

/**
 * ダッシュボード用のローカル Room キャッシュを更新する worker。
 * 通常同期は「今日 + 過去 7 日」を取り込み、HR/Vital 時系列は当日 + 昨日のみ書き込む。
 * Room 更新後に Firestore へ日次集約を upsert する (機種変対応のため正本を分散)。
 *
 * Periodic (1h) と foreground 即時の両方で同じ doWork を共有する。
 * SessionConfig の restingBpm 自動同期もここで行う (旧 HealthIngestWorker から統合)。
 */
class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = HealthDataSource(applicationContext)
        if (!source.available) {
            Log.i(TAG, "HC 利用不可なので sync skip")
            return Result.success()
        }
        if (source.grantedPermissions().isEmpty()) {
            Log.i(TAG, "HC 権限なし → sync skip")
            return Result.success()
        }

        val today = LocalDate.now()
        runCatching { runNormalSync(applicationContext, today) }
            .onFailure {
                Log.w(TAG, "Room 同期失敗", it)
                return Result.retry()
            }

        // Firestore は日次集約のみ。Room から派生して upload する (HC 二重読み込みを避ける)
        val repo = DashboardRepository(applicationContext)
        val records = repo.snapshotsAsRecords(DashboardSyncManager.NORMAL_WINDOW_DAYS, today)
        var anyFailed = false
        records.forEach { record ->
            if (!FirestoreSync.upsertDailyHealth(record)) anyFailed = true
        }
        // 直近の RHR を SessionConfig.restingBpm に反映 (Karvonen 入力として日々変動を吸収)
        syncRestingBpm(records.firstNotNullOfOrNull { it.restingHeartRateBpm })

        Log.i(TAG, "sync 完了 (${records.size} days, firestore failed=$anyFailed)")
        return if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun syncRestingBpm(latestRhr: Int?) {
        if (latestRhr == null) return
        // SessionConfig の require レンジ (30..120) を外れる値は無視。HC 側の異常値ガード
        if (latestRhr !in 30..120) {
            Log.w(TAG, "HC RHR が想定レンジ外: $latestRhr → 反映スキップ")
            return
        }
        val current = PhoneSettingsRepository(applicationContext).loadDocument().toSessionConfig()
        if (current.restingBpm == latestRhr) return
        PhoneSync.updateSettingsEverywhere(applicationContext) { it.copy(restingBpm = latestRhr) }
        Log.i(TAG, "restingBpm を HC 値で更新: ${current.restingBpm} → $latestRhr")
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}

package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "AdaptivePulse"

private val Context.dashboardPrefs by preferencesDataStore("dashboard_sync")
private val KEY_INITIAL_SYNC_COMPLETED_AT = longPreferencesKey("initial_sync_completed_at_ms")

/**
 * ダッシュボード同期の入口。
 *  - [enqueuePeriodic]: 1 時間ごとに「今日 + 過去 7 日」を Room へ同期
 *  - [enqueueForeground]: アプリ前景化時に即時 1 回
 *  - [enqueueInitialSyncIfNeeded]: 初回のみ 5 年遡及。完了マークは DataStore 永続
 *  - [cancelAll]: HC 連携を切ったときに全停止
 */
object DashboardSyncManager {

    const val WORK_PERIODIC = "dashboard-sync-periodic"
    const val WORK_FOREGROUND = "dashboard-sync-foreground"
    const val WORK_INITIAL = "dashboard-sync-initial"

    /** 通常同期で取り込む過去日数 (今日含む) */
    const val NORMAL_WINDOW_DAYS = 8

    /** 初回 backfill で取り込む過去日数。HC `READ_HEALTH_DATA_HISTORY` が許可されていないと 30 日で頭打ち */
    const val INITIAL_WINDOW_DAYS = 1825

    fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkOptional())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 前景化で即時同期 1 回。既に走っていれば KEEP で重複しない */
    fun enqueueForeground(context: Context) {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(networkOptional())
            .setInputData(workDataOf(HealthSyncWorker.KEY_REASON to "foreground"))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_FOREGROUND,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** インストール後初回のみ起動する 5 年 backfill。冪等で複数回呼んでも 1 度だけ走る */
    suspend fun enqueueInitialSyncIfNeeded(context: Context) {
        val completed = context.dashboardPrefs.data
            .map { it[KEY_INITIAL_SYNC_COMPLETED_AT] }
            .firstOrNull()
        if (completed != null && completed > 0L) {
            Log.i(TAG, "初回 sync 既完了 (${completed} ms) → skip")
            return
        }
        val request = OneTimeWorkRequestBuilder<InitialSyncWorker>()
            .setConstraints(networkOptional())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_INITIAL,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun markInitialSyncCompleted(context: Context) {
        context.dashboardPrefs.edit {
            it[KEY_INITIAL_SYNC_COMPLETED_AT] = System.currentTimeMillis()
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_PERIODIC)
            cancelUniqueWork(WORK_FOREGROUND)
            cancelUniqueWork(WORK_INITIAL)
        }
    }

    /** Firestore upload は network 要 / HC read は不要なので、CONNECTED があれば走らせる */
    private fun networkOptional() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

/** 通常同期 (periodic + foreground 共通) のロジックを 1 か所に集約 */
internal suspend fun runNormalSync(context: Context, today: LocalDate = LocalDate.now()) {
    val repo = DashboardRepository(context)
    // 過去 7 日 (今日含む) を Room へ。HR/Vital 時系列は当日 + 昨日のみに絞る (容量制御)
    (0 until DashboardSyncManager.NORMAL_WINDOW_DAYS).forEach { offset ->
        val day = today.minusDays(offset.toLong())
        val withTimeSeries = offset <= 1 // 今日 + 昨日
        runCatching { repo.syncDay(day, includeTimeSeries = withTimeSeries) }
            .onFailure { Log.w(TAG, "syncDay 失敗: $day", it) }
    }
    repo.pruneOldTimeSeries(today)
}

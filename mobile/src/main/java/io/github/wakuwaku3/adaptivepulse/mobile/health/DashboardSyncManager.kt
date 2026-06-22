package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val TAG = "AdaptivePulse"

/**
 * ダッシュボード同期の入口。
 *  - [enqueuePeriodic]: 1 時間ごとに「今日 + 過去 7 日」を Room へ同期
 *  - [enqueueForeground]: アプリ前景化時に即時 1 回
 *  - [enqueueInitialSyncIfNeeded]: 初回のみ 5 年遡及。完了判定は Room の最古日から導く
 *    (DataStore に別管理しないことで destructive migration からの自動再 backfill が効く)
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

    /**
     * Room の最古日が「直近の periodic 窓内」だった場合のみ 5 年 backfill を起動する。
     * 既に backfill 済 (Room に古い日が残っている) なら何もしない。冪等。
     *
     * 完了判定を DataStore でなく Room 由来にしているのは、Room destructive migration
     * (schema 版 up) で Room だけが wipe されたケースを自動回復させるため。
     */
    suspend fun enqueueInitialSyncIfNeeded(context: Context) {
        val oldest = DashboardRepository(context).oldestSnapshotDate()
        val threshold = LocalDate.now().minusDays(NORMAL_WINDOW_DAYS + 7L)
            .toString()
        if (oldest != null && oldest < threshold) {
            Log.i(TAG, "Room に過去 backfill 済 (oldest=$oldest) → initial sync skip")
            return
        }
        Log.i(TAG, "Room の過去データ不足 (oldest=$oldest) → initial sync enqueue")
        enqueueInitialSyncWork(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * ユーザ操作 (Settings の resync ボタン) からの強制 enqueue。Room 状態を見ずに必ず走る。
     * REPLACE で既存ワーカーを上書きし「押した瞬間に開始」感を出す。
     */
    fun enqueueInitialSync(context: Context) {
        Log.i(TAG, "ユーザ要求による initial sync 強制 enqueue")
        enqueueInitialSyncWork(context, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueInitialSyncWork(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<InitialSyncWorker>()
            .setConstraints(networkOptional())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_INITIAL,
            policy,
            request,
        )
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
    // TDEE 再計算で自社 HIIT セッションを使うので、ループ前に 1 回だけ集める
    val sessionsByDate = repo.loadAppSessionsByDate()
    // 過去 7 日 (今日含む) を Room へ。HR/Vital 時系列は当日 + 昨日のみに絞る (容量制御)
    (0 until DashboardSyncManager.NORMAL_WINDOW_DAYS).forEach { offset ->
        val day = today.minusDays(offset.toLong())
        val withTimeSeries = offset <= 1 // 今日 + 昨日
        val daySessions = sessionsByDate[day.toString()].orEmpty()
        runCatching {
            repo.syncDay(day, includeTimeSeries = withTimeSeries, appSessionsForDate = daySessions)
        }.onFailure { Log.w(TAG, "syncDay 失敗: $day", it) }
    }
    repo.pruneOldTimeSeries(today)
}

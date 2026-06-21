package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import java.time.LocalDate

private const val TAG = "AdaptivePulse"

/**
 * インストール後 1 回だけ走る backfill。過去 5 年 (1825 日) の日次集約を Room に取り込む。
 * HC 側に該当データが無ければ null まみれの行になるが、それは正しい挙動 (推定で埋めない)。
 * 時系列レコード (HR/Vital) は対象外 (容量爆発を避ける)。
 *
 * 完了マークを DataStore に書いた後は呼び直しても [DashboardSyncManager.enqueueInitialSyncIfNeeded]
 * が skip するので冪等。
 */
class InitialSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = HealthDataSource(applicationContext)
        if (!source.available || source.grantedPermissions().isEmpty()) {
            Log.i(TAG, "HC 不可 or 権限なし → initial sync skip")
            return Result.success()
        }
        val repo = DashboardRepository(applicationContext)
        val today = LocalDate.now()
        // 5 年遡及だが、自社 HIIT は限られた件数なので 1 回まとめて読む (N+1 回避)
        val sessionsByDate = repo.loadAppSessionsByDate()
        var ok = 0
        var failed = 0
        (0 until DashboardSyncManager.INITIAL_WINDOW_DAYS).forEach { offset ->
            val day = today.minusDays(offset.toLong())
            val daySessions = sessionsByDate[day.toString()].orEmpty()
            runCatching {
                repo.syncDay(day, includeTimeSeries = false, appSessionsForDate = daySessions)
            }
                .onSuccess { ok++ }
                .onFailure {
                    failed++
                    Log.w(TAG, "initial syncDay 失敗: $day", it)
                }
        }
        Log.i(TAG, "initial sync 完了 ok=$ok failed=$failed")

        // Firestore に過去日次集約も上げておく (端末横断の正本を作るため。Spark 20K writes/日内)
        val records = repo.snapshotsAsRecords(DashboardSyncManager.INITIAL_WINDOW_DAYS, today)
        var fsFailed = 0
        records.forEach { rec ->
            if (!FirestoreSync.upsertDailyHealth(rec)) fsFailed++
        }
        Log.i(TAG, "initial Firestore upload: ${records.size - fsFailed}/${records.size}")

        DashboardSyncManager.markInitialSyncCompleted(applicationContext)
        return Result.success()
    }
}

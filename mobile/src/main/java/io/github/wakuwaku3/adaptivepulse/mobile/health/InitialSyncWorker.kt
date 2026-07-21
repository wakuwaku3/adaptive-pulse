package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import java.time.LocalDate

private const val TAG = "AdaptivePulse"

/**
 * 過去 5 年 (1825 日) の日次集約を Room に backfill する worker。
 * HC 側に該当データが無ければ null まみれの行になるが、それは正しい挙動 (推定で埋めない)。
 * 時系列レコード (HR/Vital) は対象外 (容量爆発を避ける)。
 *
 * 既に実測データ入りで確定した過去日は再読しない (過去日は修正されない前提。2026-07-05)。
 * HC が実レコード無しでも合成する BMR 由来カロリーだけの行は「読めた日」と見なさず
 * 再読対象に残す (`DailyHealthRecord.hasMeasuredData`)。
 * HC のレート制限や WorkManager の約 10 分制限で途中停止しても、再実行が
 * 「欠けた日だけ埋める」動作になり冪等・再開可能になる。Firestore への反映も
 * 行単位の uploadedAtMs マークで再開可能 (`DashboardRepository.flushUnuploaded`)。
 *
 * 起動判定 (= 再 backfill が必要か) は Room の最古日で行うので、本 worker 側に
 * 完了マークを残す必要はない (`DashboardSyncManager.enqueueInitialSyncIfNeeded` 参照)。
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
        // 直近の通常同期窓は修正されうるので毎回読み直し、それより古いデータ入りの日は skip する
        val cachedDates = repo.datesWithData()
        var ok = 0
        var failed = 0
        var skipped = 0
        (0 until DashboardSyncManager.INITIAL_WINDOW_DAYS).forEach { offset ->
            val day = today.minusDays(offset.toLong())
            val dayStr = day.toString()
            if (offset >= DashboardSyncManager.NORMAL_WINDOW_DAYS && dayStr in cachedDates) {
                skipped++
                return@forEach
            }
            val daySessions = sessionsByDate[dayStr].orEmpty()
            runCatching {
                repo.syncDay(day, includeTimeSeries = false, appSessionsForDate = daySessions)
            }
                .onSuccess { ok++ }
                .onFailure {
                    failed++
                    Log.w(TAG, "initial syncDay 失敗: $day", it)
                }
        }
        Log.i(TAG, "initial sync 完了 ok=$ok failed=$failed skipped=$skipped")

        // Firestore へは「未反映行の flush」で上げる (端末横断の正本を作るため)。
        // 行単位の uploadedAtMs マークなので、本 worker が途中停止しても取りこぼさず、
        // 反映済みの日を再アップロードして Spark 20K writes/日の枠を浪費することもない
        val fsFailed = repo.flushUnuploaded()
        if (fsFailed > 0) Log.w(TAG, "initial Firestore flush 失敗 $fsFailed 件 (次回 sync で再試行)")

        return Result.success()
    }
}

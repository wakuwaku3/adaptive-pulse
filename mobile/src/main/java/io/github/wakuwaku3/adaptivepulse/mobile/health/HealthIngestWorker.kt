package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private const val TAG = "AdaptivePulse"

/**
 * Health Connect から日次の健康指標を Firestore に同期する。
 *  - 初回連携時の back-fill: [scheduleBackfill] で one-time、過去 N 日分。
 *  - 日次同期: [scheduleDaily] で periodic、各日「直近 2 日 (昨日 + 一昨日)」を冪等 upsert。
 *
 * Doc id を date 固定にしているので、同じ日付の重複取得は単純な上書きになる
 * (back-fill と periodic がオーバーラップしても安全)。
 *
 * WorkManager 経由のためアプリが起動していなくても OS が条件 (NETWORK_CONNECTED) を
 * 満たした時に走る。force-stop されている間は走らないが、ユーザが phone を一度開けば
 * 復帰する。
 */
class HealthIngestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = inputData.getInt(KEY_DAYS, DEFAULT_DAYS)
        val source = HealthDataSource(applicationContext)
        if (!source.available) {
            Log.i(TAG, "HC が利用不可なので ingest skip")
            return Result.success()
        }
        val granted = source.grantedPermissions()
        if (granted.isEmpty()) {
            Log.i(TAG, "HC 権限が無いので ingest skip")
            return Result.success()
        }

        val records = source.readDailySummaries(days)
        var anyFailed = false
        records.forEach { record ->
            if (!FirestoreSync.upsertDailyHealth(record)) {
                anyFailed = true
            }
        }
        return if (anyFailed) {
            Log.w(TAG, "一部の upsert に失敗 → WorkManager に retry を任せる")
            Result.retry()
        } else {
            Log.i(TAG, "dailyMetrics を ${records.size} 件 upsert 完了")
            Result.success()
        }
    }

    companion object {
        const val KEY_DAYS = "days"
        const val DEFAULT_DAYS = 2
        /** 初回 back-fill の対象期間 (Pixel Watch のデータ保持期間と要相談だが 90 日あれば直近トレンドは見える) */
        const val BACKFILL_DAYS = 90
        const val WORK_BACKFILL = "health-connect-backfill"
        const val WORK_DAILY = "health-connect-daily"

        /** 初回連携時の過去データ取り込み (one-time)。非同期で走る = ユーザは続けて操作できる */
        fun scheduleBackfill(context: Context, days: Int = BACKFILL_DAYS) {
            val request = OneTimeWorkRequestBuilder<HealthIngestWorker>()
                .setInputData(workDataOf(KEY_DAYS to days))
                .setConstraints(networkConstraint())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_BACKFILL,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** 1 日 1 回の継続同期 (periodic)。初回は翌朝 06:00 (ローカル) から */
        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthIngestWorker>(1, TimeUnit.DAYS)
                .setInputData(workDataOf(KEY_DAYS to DEFAULT_DAYS))
                .setConstraints(networkConstraint())
                .setInitialDelay(minutesUntilNextMorning(), TimeUnit.MINUTES)
                .build()
            // 既存スケジュールはそのまま保つ (KEEP) ことで「toggle ON のたびに翌日扱い」を避ける
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(WORK_BACKFILL)
                cancelUniqueWork(WORK_DAILY)
            }
        }

        private fun networkConstraint() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun minutesUntilNextMorning(hour: Int = 6): Long {
            val now = ZonedDateTime.now()
            val today6 = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
            val target = if (today6.isAfter(now)) today6 else today6.plusDays(1)
            return Duration.between(now, target).toMinutes()
        }
    }
}

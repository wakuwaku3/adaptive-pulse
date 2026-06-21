package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.github.wakuwaku3.adaptivepulse.core.sync.HealthDataExport
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.mobile.calories.CalorieEnricher
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"

/**
 * Health Connect の日次集計と本アプリのセッション履歴を 1 つの JSON にまとめて、
 * 標準の Share Intent で外部に渡せるようにする (Claude にコピペしたり Drive に
 * 投げたりするための「データ取り出し口」)。
 *
 * 出力は app の cache 下に書き、FileProvider 経由で他アプリと共有する。
 * 認証/ネットワーク経路は壊れていても落ちないように、Firestore 読み込みは失敗
 * すれば空リストにフォールバックする。
 */
class HealthDataExporter(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun build(days: Int, zone: ZoneId = ZoneId.systemDefault()): HealthDataExport {
        val today = LocalDate.now(zone)
        val from = today.minusDays(days.toLong())
        val to = today.minusDays(1) // 昨日 (HC は今日分が日中まだ揃わないため)

        val source = HealthDataSource(context)
        val raw = source.readDailySummaries(days, zone).sortedBy { it.date }

        val sessions = listSessions(from, to).sortedBy { it.startedAtMs }
        val appSessionsByDate = sessions.groupBy {
            Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate().toString()
        }
        // 全期間の HC ExerciseSession を 1 回で取って日付別に bucket する (N+1 回避)
        val rangeFrom = from.atStartOfDay(zone)
        val rangeTo = today.atStartOfDay(zone)
        val hcSessionsByDate = source.readExerciseSessions(rangeFrom, rangeTo)
            .groupBy {
                Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate().toString()
            }

        val dailyMetrics = raw.map { rec ->
            CalorieEnricher.enrich(
                record = rec,
                hcSessions = hcSessionsByDate[rec.date].orEmpty(),
                appSessions = appSessionsByDate[rec.date].orEmpty(),
            )
        }

        return HealthDataExport(
            exportedAtMs = System.currentTimeMillis(),
            fromDate = from.toString(),
            toDate = to.toString(),
            dailyMetrics = dailyMetrics,
            sessions = sessions,
        )
    }

    /** [from] 〜 [to] (inclusive) のセッションを Firestore + ローカル pending から集める */
    private suspend fun listSessions(from: LocalDate, to: LocalDate): List<SessionRecord> {
        val fromMs = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Firestore は無いことも想定する (未サインイン/未設定) ので失敗時は空にフォールバック
        val remote = FirestoreSync.listSessions(limit = 500).orEmpty()
        val local = PendingSessionStore(context).list()
        return (remote + local)
            .distinctBy { it.id }
            .filter { it.startedAtMs in fromMs until toMs }
    }

    /** 共有用に JSON をファイル化して Intent.ACTION_SEND を返す */
    fun shareIntent(export: HealthDataExport): Intent {
        val file = writeToCache(export)
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AdaptivePulse export ${export.fromDate}〜${export.toDate}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export health data")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun writeToCache(export: HealthDataExport): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "adaptivepulse-${export.fromDate}_${export.toDate}.json")
        file.writeText(json.encodeToString(HealthDataExport.serializer(), export))
        Log.i(TAG, "Health データを export: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }
}

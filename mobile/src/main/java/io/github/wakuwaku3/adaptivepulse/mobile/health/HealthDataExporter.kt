package io.github.wakuwaku3.adaptivepulse.mobile.health

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.wakuwaku3.adaptivepulse.core.analysis.analyzeHealthData
import io.github.wakuwaku3.adaptivepulse.core.sync.ExportDocumentation
import io.github.wakuwaku3.adaptivepulse.core.sync.HealthDataExport
import io.github.wakuwaku3.adaptivepulse.core.sync.RawData
import io.github.wakuwaku3.adaptivepulse.mobile.settings.ExportLocation
import io.github.wakuwaku3.adaptivepulse.mobile.settings.ExportSettingsRepository
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import io.github.wakuwaku3.adaptivepulse.mobile.strength.StrengthCatalogRepository
import io.github.wakuwaku3.adaptivepulse.mobile.strength.WorkoutStore
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"

/**
 * 端末のデータを 1 つの JSON (schema 2: 生データ + analyze 相当の集計 + 自己説明) にまとめ、
 * 共有ストレージの所定パス (既定 `Download/adaptive-pulse.json`) に常に最新 1 枚として上書きする。
 * スマホの Claude / ChatGPT にそのファイルを添付して「分析して」で使うための取り出し口。
 *
 * 日次指標は HC を再読せず Room ([DashboardRepository]) から組むので安価。定期同期・前景化・
 * 手動更新のどのタイミングで呼んでも軽い。
 */
class HealthDataExporter(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** 期間 [days] 日 (今日含む) 分の生データ + 集計を組む */
    suspend fun build(days: Int = EXPORT_WINDOW_DAYS, zone: ZoneId = ZoneId.systemDefault()): HealthDataExport {
        val today = LocalDate.now(zone)
        val from = today.minusDays((days - 1).toLong())

        val dailyMetrics = DashboardRepository(context).snapshotsAsRecords(days, today).sortedBy { it.date }
        // 容量のためセッション中 1 秒 HR 列は落とす (集計にも使わない)
        val sessions = listSessions(from, today).sortedBy { it.startedAtMs }
            .map { it.copy(hrBpmBySecond = null) }
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val workouts = WorkoutStore(context).all()
            .filter { it.startedAtMs in fromMs until toMs }
            .sortedBy { it.startedAtMs }
        val settings = PhoneSettingsRepository(context).loadDocument()
        val catalog = StrengthCatalogRepository(context).load()

        return HealthDataExport(
            exportedAtMs = System.currentTimeMillis(),
            fromDate = from.toString(),
            toDate = today.toString(),
            documentation = ExportDocumentation.default(),
            raw = RawData(
                dailyMetrics = dailyMetrics,
                sessions = sessions,
                workouts = workouts,
                settings = settings,
                strengthCatalog = catalog,
            ),
            analysis = analyzeHealthData(dailyMetrics, sessions, workouts, settings, zone),
        )
    }

    /** ビルドして設定の保存先へ上書き保存する。保存先の表示パスを返す (呼び出し側で表示用) */
    suspend fun exportToConfiguredLocation(): String {
        val export = build()
        val location = ExportSettingsRepository(context).load()
        val content = json.encodeToString(HealthDataExport.serializer(), export)
        return writeToDownloads(content, location)
    }

    /** [from] 〜 [to] (inclusive) のセッションを Firestore + ローカル pending から集める */
    private suspend fun listSessions(from: LocalDate, to: LocalDate) =
        run {
            val fromMs = from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val toMs = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            // Firestore は無いことも想定する (未サインイン/未設定) ので失敗時は空にフォールバック
            val remote = FirestoreSync.listSessions(limit = 500).orEmpty()
            val local = PendingSessionStore(context).list()
            (remote + local).distinctBy { it.id }.filter { it.startedAtMs in fromMs until toMs }
        }

    /**
     * 共有ストレージの Download 配下へ「常に最新 1 枚」を上書きする。同名エントリがあれば
     * 更新し (増殖させない)、無ければ作る。Android 10 未満は権限が要るので app 専用領域に退避する。
     */
    private fun writeToDownloads(content: String, location: ExportLocation): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(content, location)
        } else {
            val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(dir, location.fileName)
            file.writeText(content)
            Log.i(TAG, "export (legacy) 保存: ${file.absolutePath}")
            file.absolutePath
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(content: String, location: ExportLocation): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = location.relativePath.trim('/') + "/"
        val uri = findExisting(collection, relativePath, location.fileName)
            ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, location.fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                },
            )
            ?: error("MediaStore insert が null")
        // "wt" = truncate してから書く (前回より短くなっても末尾が残らない)
        resolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
            ?: error("openOutputStream が null: $uri")
        Log.i(TAG, "export 保存: $relativePath${location.fileName} (${content.length} bytes)")
        return "$relativePath${location.fileName}"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findExisting(collection: Uri, relativePath: String, fileName: String): Uri? {
        val selection =
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(relativePath, fileName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    companion object {
        /** エクスポートに含める過去日数 (今日含む)。体組成・筋トレの推移が見える長さ */
        const val EXPORT_WINDOW_DAYS = 30
    }
}

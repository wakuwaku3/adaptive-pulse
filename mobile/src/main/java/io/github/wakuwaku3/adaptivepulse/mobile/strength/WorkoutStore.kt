package io.github.wakuwaku3.adaptivepulse.mobile.strength

import android.content.Context
import io.github.wakuwaku3.adaptivepulse.core.strength.WorkoutRecord
import java.io.File
import kotlinx.serialization.json.Json

private const val MAX_WORKOUTS = 500

/**
 * workout レコードのローカル永続化。PendingSessionStore と違いファイルが正本で、
 * Firestore へ upload 済みでも消さない (Firestore は分析用バックアップで読み戻さないため、
 * 中断再開はローカルから行う)。
 * 未 upload 分は dirty マーカーで追跡し、画面 open 時に再送する。
 */
class WorkoutStore(context: Context) {

    private val dir = File(context.filesDir, "workouts").apply { mkdirs() }
    private val dirtyDir = File(context.filesDir, "workouts-dirty").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun save(record: WorkoutRecord) {
        File(dir, "${record.id}.json")
            .writeText(json.encodeToString(WorkoutRecord.serializer(), record))
        File(dirtyDir, record.id).writeText("")
        // id は "<startedAtMs>-<rand>" なので名前降順 = 新しい順。古い分から容量を守る
        dir.listFiles().orEmpty().sortedByDescending { it.name }
            .drop(MAX_WORKOUTS).forEach {
                File(dirtyDir, it.nameWithoutExtension).delete()
                it.delete()
            }
    }

    fun latest(): WorkoutRecord? =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .maxByOrNull { it.name }?.let(::read)

    fun all(): List<WorkoutRecord> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull(::read)

    fun dirty(): List<WorkoutRecord> =
        dirtyDir.listFiles().orEmpty().mapNotNull { marker ->
            File(dir, "${marker.name}.json").takeIf { it.exists() }?.let(::read)
        }

    fun markUploaded(id: String) {
        File(dirtyDir, id).delete()
    }

    private fun read(file: File): WorkoutRecord? =
        runCatching { json.decodeFromString(WorkoutRecord.serializer(), file.readText()) }
            .getOrNull()
}

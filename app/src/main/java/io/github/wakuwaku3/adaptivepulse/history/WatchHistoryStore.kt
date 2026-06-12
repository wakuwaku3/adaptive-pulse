package io.github.wakuwaku3.adaptivepulse.history

import android.content.Context
import android.util.Log
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import java.io.File
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"
private const val MAX_RECORDS = 200

/**
 * watch ローカルのセッション履歴 (1 件 = 1 JSON ファイル)。
 * 正本はサーバー (phone 経由で同期) で、ここは直近分のキャッシュ。
 */
class WatchHistoryStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun save(record: SessionRecord) {
        File(dir, "${record.id}.json")
            .writeText(json.encodeToString(SessionRecord.serializer(), record))
        prune()
    }

    fun list(): List<SessionRecord> =
        dir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { json.decodeFromString(SessionRecord.serializer(), file.readText()) }
                    .getOrElse {
                        Log.w(TAG, "履歴ファイルの解釈に失敗: ${file.name}", it)
                        null
                    }
            }
            .sortedByDescending { it.startedAtMs }

    private fun prune() {
        dir.listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_RECORDS)
            .forEach { it.delete() }
    }
}

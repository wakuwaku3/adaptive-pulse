package io.github.wakuwaku3.adaptivepulse.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"

/**
 * Wearable Data Layer への書き込み (watch 側)。DataItem はローカル優先で永続化され、
 * phone と接続されたときに自動で同期されるため、オフライン (ジム) でも失敗しない。
 */
object WearSync {

    val json = Json { ignoreUnknownKeys = true }

    /** セッション記録を phone へ送る。phone がサーバー反映後に DataItem を削除する */
    suspend fun putSession(context: Context, record: SessionRecord) {
        putItem(context, SyncPaths.session(record.id), json.encodeToString(SessionRecord.serializer(), record))
    }

    /** 設定の現在値を共有する (LWW。受け手は updatedAtMs が新しいときだけ適用) */
    suspend fun putSettings(context: Context, doc: SettingsDocument) {
        putItem(context, SyncPaths.SETTINGS, json.encodeToString(SettingsDocument.serializer(), doc))
    }

    private suspend fun putItem(context: Context, path: String, payload: String) {
        runCatching {
            val request = PutDataRequest.create(path).apply {
                data = payload.toByteArray(Charsets.UTF_8)
                setUrgent()
            }
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onFailure {
            // 同期失敗でセッション完了や設定変更を巻き込まない (DataItem は次回接続で再送される)
            Log.w(TAG, "Data Layer への書き込みに失敗: $path", it)
        }
    }
}

/** ローカル起点の設定変更を保存し、phone へ共有する (UI と debug receiver の共通経路) */
suspend fun updateSettingsAndSync(
    context: Context,
    transform: (SessionConfig) -> SessionConfig,
) {
    val doc = SettingsRepository(context).update(transform)
    WearSync.putSettings(context, doc)
}

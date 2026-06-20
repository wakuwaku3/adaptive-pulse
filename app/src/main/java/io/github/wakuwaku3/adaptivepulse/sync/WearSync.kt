package io.github.wakuwaku3.adaptivepulse.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
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

    /** ライブセッション状態を phone へ書く (最新スナップショットの上書き保存) */
    suspend fun putLiveSnapshot(context: Context, snapshot: SessionLiveSnapshot) {
        putItem(
            context,
            SyncPaths.SESSION_LIVE,
            json.encodeToString(SessionLiveSnapshot.serializer(), snapshot),
        )
    }

    /** セッション終了時にライブ DataItem を消す (phone のライブ画面を自動で閉じるトリガー) */
    suspend fun deleteLiveSnapshot(context: Context) {
        runCatching {
            // wear://*/<path> の host=local node 形式で削除を投げる
            val uri = Uri.parse("wear:" + SyncPaths.SESSION_LIVE)
            Wearable.getDataClient(context).deleteDataItems(uri).await()
        }.onFailure { Log.w(TAG, "ライブ DataItem の削除に失敗", it) }
    }

    /** phone を前面化する ping (MessageClient: 一発撃って忘れる) */
    suspend fun sendStartForeground(context: Context) {
        runCatching {
            val nodes: List<Node> = Wearable.getNodeClient(context).connectedNodes.await()
            val messageClient = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, SyncPaths.SESSION_START_FOREGROUND, ByteArray(0))
                    .await()
            }
        }.onFailure { Log.w(TAG, "start-foreground ping の送信に失敗", it) }
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

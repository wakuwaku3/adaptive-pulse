package io.github.wakuwaku3.adaptivepulse.mobile.sync

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

private const val TAG = "AdaptivePulse"

/**
 * watch からの履歴・設定を受信する (phone 側の受信口)。
 * セッションは永続キューに保存してから DataItem を削除し (受領 ack)、
 * Firestore への反映はキューから行う (失敗してもアプリ起動時に再送される)。
 */
class PhoneSyncService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            when {
                path.startsWith(SyncPaths.SESSIONS_PREFIX) ->
                    onSessionReceived(event.dataItem.uri, event.dataItem.data)
                path == SyncPaths.SETTINGS -> onSettingsReceived(event.dataItem.data)
            }
        }
    }

    private fun onSessionReceived(uri: Uri, payload: ByteArray?) {
        val record = payload?.let {
            runCatching {
                PhoneSync.json.decodeFromString(
                    SessionRecord.serializer(),
                    it.toString(Charsets.UTF_8),
                )
            }.onFailure { e -> Log.w(TAG, "セッション DataItem の解釈に失敗", e) }.getOrNull()
        } ?: return

        runBlocking {
            PendingSessionStore(applicationContext).save(record)
            // 永続キューに乗ったので DataItem は削除してよい (受領 ack)
            runCatching { Wearable.getDataClient(applicationContext).deleteDataItems(uri).await() }
            val remaining = PhoneSync.syncPendingSessions(applicationContext)
            Log.i(TAG, "セッション受信: ${record.id} (未同期 $remaining 件)")
        }
    }

    private fun onSettingsReceived(payload: ByteArray?) {
        val doc = payload?.let {
            runCatching {
                PhoneSync.json.decodeFromString(
                    SettingsDocument.serializer(),
                    it.toString(Charsets.UTF_8),
                )
            }.onFailure { e -> Log.w(TAG, "設定 DataItem の解釈に失敗", e) }.getOrNull()
        } ?: return

        runBlocking {
            val applied = PhoneSettingsRepository(applicationContext).replaceIfNewer(doc)
            if (applied) {
                Log.i(TAG, "watch からの設定を適用: $doc")
                FirestoreSync.putSettings(doc)
            }
        }
    }
}

package io.github.wakuwaku3.adaptivepulse.mobile.sync

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.mobile.library.PhoneLibraryRepository
import io.github.wakuwaku3.adaptivepulse.mobile.session.LiveSessionLauncher
import io.github.wakuwaku3.adaptivepulse.mobile.session.LiveSessionStore
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
            val path = event.dataItem.uri.path ?: continue
            when {
                // ライブ状態は DELETE もハンドルしてライブ画面を閉じる
                path == SyncPaths.SESSION_LIVE -> when (event.type) {
                    DataEvent.TYPE_CHANGED -> onLiveSnapshotReceived(event.dataItem.data)
                    DataEvent.TYPE_DELETED -> LiveSessionStore.clear()
                }
                event.type != DataEvent.TYPE_CHANGED -> continue
                path.startsWith(SyncPaths.SESSIONS_PREFIX) ->
                    onSessionReceived(event.dataItem.uri, event.dataItem.data)
                path == SyncPaths.SETTINGS -> onSettingsReceived(event.dataItem.data)
                path == SyncPaths.LIBRARY -> onLibraryReceived(event.dataItem.data)
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == SyncPaths.SESSION_START_FOREGROUND) {
            // watch が今セッションを開始した → phone をライブ画面で前面化する
            LiveSessionLauncher.notifyAndLaunch(applicationContext)
        }
    }

    private fun onLiveSnapshotReceived(payload: ByteArray?) {
        val snapshot = payload?.let {
            runCatching {
                PhoneSync.json.decodeFromString(
                    SessionLiveSnapshot.serializer(),
                    it.toString(Charsets.UTF_8),
                )
            }.onFailure { e -> Log.w(TAG, "live DataItem の解釈に失敗", e) }.getOrNull()
        } ?: return
        LiveSessionStore.update(snapshot)
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

    private fun onLibraryReceived(payload: ByteArray?) {
        val doc = payload?.let {
            runCatching {
                PhoneSync.json.decodeFromString(
                    LibraryDocument.serializer(),
                    it.toString(Charsets.UTF_8),
                )
            }.onFailure { e -> Log.w(TAG, "ライブラリ DataItem の解釈に失敗", e) }.getOrNull()
        } ?: return

        runBlocking {
            val applied = PhoneLibraryRepository(applicationContext).replaceIfNewer(doc)
            if (applied) {
                Log.i(TAG, "watch からのライブラリを適用 (selection=${doc.selection})")
                FirestoreSync.putLibrary(doc)
            }
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

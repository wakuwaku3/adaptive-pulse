package io.github.wakuwaku3.adaptivepulse.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.session.SessionService
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import kotlinx.coroutines.runBlocking

private const val TAG = "AdaptivePulse"

/**
 * phone 起点の設定変更 (DataClient) と セッション遠隔操作 (MessageClient) を捌く。
 * 自分の put も イベントとして届くが、updatedAtMs が同値なので no-op になる。
 */
class WatchSyncService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != SyncPaths.SETTINGS) continue
            val doc = runCatching {
                WearSync.json.decodeFromString(
                    SettingsDocument.serializer(),
                    item.data!!.toString(Charsets.UTF_8),
                )
            }.onFailure { Log.w(TAG, "設定 DataItem の解釈に失敗", it) }.getOrNull()
            if (doc == null) continue
            // listener コールバックは binder スレッド。書き込みは小さく即時
            val applied = runBlocking { SettingsRepository(applicationContext).replaceIfNewer(doc) }
            if (applied) Log.i(TAG, "phone からの設定を適用: $doc")
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            SyncPaths.SESSION_CMD_STOP -> {
                Log.i(TAG, "phone からセッション停止コマンド")
                SessionService.stop(applicationContext)
            }
            SyncPaths.SESSION_CMD_DONE -> {
                Log.i(TAG, "phone から Done 確認コマンド")
                SessionService.done(applicationContext)
            }
            SyncPaths.SESSION_CMD_THRESHOLD -> {
                val delta = event.data.toIntDelta() ?: return
                SessionService.adjustActiveThreshold(delta)
            }
        }
    }

    /** payload は 4 byte の符号付き Int (big-endian) を期待。形が違えば無視 */
    private fun ByteArray.toIntDelta(): Int? {
        if (size != 4) return null
        return ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
    }
}

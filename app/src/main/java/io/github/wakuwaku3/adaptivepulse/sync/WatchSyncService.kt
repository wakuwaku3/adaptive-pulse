package io.github.wakuwaku3.adaptivepulse.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import kotlinx.coroutines.runBlocking

private const val TAG = "AdaptivePulse"

/**
 * phone 起点の設定変更を受けて LWW で適用する (watch 側の受信口)。
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
}

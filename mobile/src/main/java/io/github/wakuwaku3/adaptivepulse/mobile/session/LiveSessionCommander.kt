package io.github.wakuwaku3.adaptivepulse.mobile.session

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AdaptivePulse"

/**
 * phone → watch の遠隔操作 (`MessageClient`)。
 * fire-and-forget。失敗はログのみで UI は壊さない (watch 側のセッションが本物の真実)。
 *
 * 連射時の取りこぼしを避けるため、呼び出しは即時に scope で送る (UI thread はブロックしない)。
 */
object LiveSessionCommander {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun stop(context: Context) = send(context, SyncPaths.SESSION_CMD_STOP, ByteArray(0))

    /** Done 確認: live snapshot を消して phone を dashboard に戻す (Finished 画面から) */
    fun done(context: Context) = send(context, SyncPaths.SESSION_CMD_DONE, ByteArray(0))

    fun adjustThreshold(context: Context, delta: Int) =
        send(context, SyncPaths.SESSION_CMD_THRESHOLD, encodeInt(delta))

    private fun send(context: Context, path: String, payload: ByteArray) {
        scope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                val client = Wearable.getMessageClient(context)
                nodes.forEach { node ->
                    client.sendMessage(node.id, path, payload).await()
                }
            }.onFailure { Log.w(TAG, "cmd 送信に失敗: $path", it) }
        }
    }

    /** 32-bit signed Int を big-endian の 4 byte で送る (受け手は同じ規約で復号) */
    private fun encodeInt(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )
}

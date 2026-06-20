package io.github.wakuwaku3.adaptivepulse.mobile.session

import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * watch から流れてくるライブ状態の単一保持先。
 * `PhoneSyncService` が書き、UI が StateFlow で購読する。
 * プロセス内に閉じる in-memory 状態 (永続化不要。次回起動時は watch が現在状態を再放送するか、何もしない)。
 */
object LiveSessionStore {

    private val _snapshot = MutableStateFlow<SessionLiveSnapshot?>(null)
    val snapshot: StateFlow<SessionLiveSnapshot?> = _snapshot

    fun update(snapshot: SessionLiveSnapshot) {
        _snapshot.value = snapshot
    }

    /** watch がセッション終了時に DataItem を消した = ライブ画面を畳むトリガー */
    fun clear() {
        _snapshot.value = null
    }
}

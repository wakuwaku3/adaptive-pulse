package io.github.wakuwaku3.adaptivepulse.core.sync

/** Wearable Data Layer の DataItem / MessageClient パス (watch / phone で共有する契約) */
object SyncPaths {
    const val SETTINGS = "/settings"
    const val SESSIONS_PREFIX = "/sessions/"

    /** DataClient: watch がセッション中のライブ状態を上書きで置く。終了時に削除 */
    const val SESSION_LIVE = "/session/live"

    /** MessageClient: watch がセッション開始時に phone を前面化する ping */
    const val SESSION_START_FOREGROUND = "/session/start-foreground"

    fun session(id: String) = "$SESSIONS_PREFIX$id"
}

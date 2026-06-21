package io.github.wakuwaku3.adaptivepulse.core.sync

/** Wearable Data Layer の DataItem / MessageClient パス (watch / phone で共有する契約) */
object SyncPaths {
    const val SETTINGS = "/settings"
    const val SESSIONS_PREFIX = "/sessions/"

    /** DataClient: watch がセッション中のライブ状態を上書きで置く。終了時に削除 */
    const val SESSION_LIVE = "/session/live"

    /** MessageClient: watch がセッション開始時に phone を前面化する ping */
    const val SESSION_START_FOREGROUND = "/session/start-foreground"

    /** MessageClient: phone から watch への遠隔操作 (停止 / 閾値ナッジ / pace target ナッジ) */
    const val SESSION_CMD_PREFIX = "/session/cmd/"
    const val SESSION_CMD_STOP = "/session/cmd/stop"
    const val SESSION_CMD_THRESHOLD = "/session/cmd/threshold"
    const val SESSION_CMD_TARGET_SPM = "/session/cmd/target-spm"

    fun session(id: String) = "$SESSIONS_PREFIX$id"
}

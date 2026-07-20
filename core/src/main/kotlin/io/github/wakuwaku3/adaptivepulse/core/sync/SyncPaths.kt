package io.github.wakuwaku3.adaptivepulse.core.sync

/** Wearable Data Layer の DataItem / MessageClient パス (watch / phone で共有する契約) */
object SyncPaths {
    const val SETTINGS = "/settings"

    /** DataClient: カスタムメニュー/プログラムと選択状態 (LibraryDocument) の LWW 同期 */
    const val LIBRARY = "/library"

    const val SESSIONS_PREFIX = "/sessions/"

    /** DataClient: watch がセッション中のライブ状態を上書きで置く。終了時に削除 */
    const val SESSION_LIVE = "/session/live"

    /** MessageClient: watch がセッション開始時に phone を前面化する ping */
    const val SESSION_START_FOREGROUND = "/session/start-foreground"

    /** MessageClient: phone から watch への遠隔操作 (停止 / Done / 閾値ナッジ) */
    const val SESSION_CMD_PREFIX = "/session/cmd/"
    const val SESSION_CMD_STOP = "/session/cmd/stop"

    /** phone 側 Done ボタン: live snapshot を消して phone を dashboard に戻すトリガー */
    const val SESSION_CMD_DONE = "/session/cmd/done"
    const val SESSION_CMD_THRESHOLD = "/session/cmd/threshold"

    fun session(id: String) = "$SESSIONS_PREFIX$id"
}

package io.github.wakuwaku3.adaptivepulse.core.sync

/** Wearable Data Layer の DataItem パス (watch / phone で共有する契約) */
object SyncPaths {
    const val SETTINGS = "/settings"
    const val SESSIONS_PREFIX = "/sessions/"

    fun session(id: String) = "$SESSIONS_PREFIX$id"
}

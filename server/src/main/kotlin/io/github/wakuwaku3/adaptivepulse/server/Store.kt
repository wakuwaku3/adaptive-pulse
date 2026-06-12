package io.github.wakuwaku3.adaptivepulse.server

import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import java.util.concurrent.ConcurrentHashMap

/**
 * 永続化の抽象。本番は Firestore (FirestoreSyncStore)、テストは in-memory。
 * LWW 等のドメイン規約はこの層の契約として実装する。
 */
interface SyncStore {

    /** ユーザープロファイルを upsert し、認証プロバイダを記録する */
    suspend fun ensureUser(user: AuthUser)

    /** セッション記録の冪等 upsert */
    suspend fun upsertSession(uid: String, record: SessionRecord)

    /** 新しい順のセッション一覧 */
    suspend fun listSessions(uid: String, limit: Int): List<SessionRecord>

    suspend fun getSettings(uid: String): SettingsDocument?

    /** LWW: updatedAtMs が保存値より新しいときだけ反映し、常に最新の正本を返す */
    suspend fun putSettingsIfNewer(uid: String, doc: SettingsDocument): SettingsDocument
}

/** テスト・ローカル開発用の in-memory 実装 */
class InMemorySyncStore : SyncStore {

    val users = ConcurrentHashMap<String, MutableSet<String>>()
    private val sessions = ConcurrentHashMap<String, MutableMap<String, SessionRecord>>()
    private val settings = ConcurrentHashMap<String, SettingsDocument>()

    override suspend fun ensureUser(user: AuthUser) {
        users.computeIfAbsent(user.uid) { mutableSetOf() }
            .apply { user.provider?.let(::add) }
    }

    override suspend fun upsertSession(uid: String, record: SessionRecord) {
        sessions.computeIfAbsent(uid) { ConcurrentHashMap() }[record.id] = record
    }

    override suspend fun listSessions(uid: String, limit: Int): List<SessionRecord> =
        sessions[uid]?.values.orEmpty()
            .sortedByDescending { it.startedAtMs }
            .take(limit)

    override suspend fun getSettings(uid: String): SettingsDocument? = settings[uid]

    override suspend fun putSettingsIfNewer(uid: String, doc: SettingsDocument): SettingsDocument =
        settings.compute(uid) { _, current ->
            if (current == null || doc.updatedAtMs > current.updatedAtMs) doc else current
        }!!
}

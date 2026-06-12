package io.github.wakuwaku3.adaptivepulse.server

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Firestore 実装 (スキーマは docs/stock/sync.md)。
 * ドキュメント本体は共有モデルの JSON 文字列 (`json` フィールド) で持ち、
 * 並べ替え・LWW 判定に使う数値だけを索引フィールドとして併置する
 * (モデル進化時にサーバーのフィールドマッピング改修を不要にするため)。
 */
class FirestoreSyncStore(private val db: Firestore) : SyncStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    override suspend fun ensureUser(user: AuthUser): Unit = withContext(Dispatchers.IO) {
        val doc = userDoc(user.uid)
        db.runTransaction { tx ->
            val snapshot = tx.get(doc).get()
            @Suppress("UNCHECKED_CAST")
            val providers =
                (snapshot.get("providers") as? List<String>).orEmpty().toMutableSet()
            user.provider?.let(providers::add)
            val now = System.currentTimeMillis()
            tx.set(
                doc,
                mapOf(
                    "providers" to providers.toList().sorted(),
                    "createdAtMs" to (snapshot.getLong("createdAtMs") ?: now),
                    "updatedAtMs" to now,
                ),
            )
        }.get()
    }

    override suspend fun upsertSession(uid: String, record: SessionRecord): Unit =
        withContext(Dispatchers.IO) {
            userDoc(uid).collection("sessions").document(record.id).set(
                mapOf(
                    "startedAtMs" to record.startedAtMs,
                    "json" to json.encodeToString(SessionRecord.serializer(), record),
                ),
            ).get()
        }

    override suspend fun listSessions(uid: String, limit: Int): List<SessionRecord> =
        withContext(Dispatchers.IO) {
            userDoc(uid).collection("sessions")
                .orderBy("startedAtMs", Query.Direction.DESCENDING)
                .limit(limit)
                .get().get()
                .documents
                .mapNotNull { doc ->
                    doc.getString("json")?.let {
                        runCatching {
                            json.decodeFromString(SessionRecord.serializer(), it)
                        }.getOrNull()
                    }
                }
        }

    override suspend fun getSettings(uid: String): SettingsDocument? =
        withContext(Dispatchers.IO) {
            userDoc(uid).collection("settings").document("current")
                .get().get()
                .getString("json")
                ?.let { json.decodeFromString(SettingsDocument.serializer(), it) }
        }

    override suspend fun putSettingsIfNewer(
        uid: String,
        doc: SettingsDocument,
    ): SettingsDocument = withContext(Dispatchers.IO) {
        val ref = userDoc(uid).collection("settings").document("current")
        db.runTransaction { tx ->
            val current = tx.get(ref).get().getString("json")
                ?.let { json.decodeFromString(SettingsDocument.serializer(), it) }
            val winner = if (current == null || doc.updatedAtMs > current.updatedAtMs) doc else current
            tx.set(
                ref,
                mapOf(
                    "updatedAtMs" to winner.updatedAtMs,
                    "json" to json.encodeToString(SettingsDocument.serializer(), winner),
                ),
            )
            winner
        }.get()
    }
}

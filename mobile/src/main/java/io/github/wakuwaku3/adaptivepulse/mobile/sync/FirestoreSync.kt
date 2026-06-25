package io.github.wakuwaku3.adaptivepulse.mobile.sync

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.wakuwaku3.adaptivepulse.core.sync.DailyHealthRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"

/**
 * 同期は Firestore SDK で本人 uid 配下を直接読み書きする (docs/stock/sync.md)。
 * 認証は Firebase Auth が暗黙に注入し、Security Rules が uid 一致と LWW を強制する
 * (firestore.rules)。未サインインや Firebase 未設定では関数は null/false を返す。
 */
object FirestoreSync {

    private val json = Json { ignoreUnknownKeys = true }

    private fun uid(): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.uid
    }.getOrNull()

    private fun db(): FirebaseFirestore? = runCatching {
        FirebaseFirestore.getInstance()
    }.getOrNull()

    private fun userDoc(uid: String) = db()?.collection("users")?.document(uid)

    /** users/{uid} に認証プロバイダを追記する (初回 + プロバイダ追加。サインイン直後に呼ぶ) */
    suspend fun ensureUser(provider: String): Boolean {
        val uid = uid() ?: return false
        val db = db() ?: return false
        val ref = userDoc(uid) ?: return false
        return runCatching {
            db.runTransaction { tx ->
                val snap = tx.get(ref)
                @Suppress("UNCHECKED_CAST")
                val providers =
                    (snap.get("providers") as? List<String>).orEmpty().toMutableSet()
                providers += provider
                val now = System.currentTimeMillis()
                tx.set(
                    ref,
                    mapOf(
                        "providers" to providers.toList().sorted(),
                        "createdAtMs" to (snap.getLong("createdAtMs") ?: now),
                        "updatedAtMs" to now,
                    ),
                )
                null
            }.await()
            true
        }.onFailure { Log.w(TAG, "ユーザープロファイルの upsert に失敗", it) }
            .getOrDefault(false)
    }

    suspend fun uploadSession(record: SessionRecord): Boolean {
        val uid = uid() ?: return false
        val ref = userDoc(uid)?.collection("sessions")?.document(record.id) ?: return false
        return runCatching {
            ref.set(
                mapOf(
                    "startedAtMs" to record.startedAtMs,
                    "json" to json.encodeToString(SessionRecord.serializer(), record),
                ),
            ).await()
            true
        }.onFailure { Log.w(TAG, "セッションのアップロードに失敗: ${record.id}", it) }
            .getOrDefault(false)
    }

    suspend fun listSessions(limit: Int = 100): List<SessionRecord>? {
        val uid = uid() ?: return null
        val col = userDoc(uid)?.collection("sessions") ?: return null
        return runCatching {
            col.orderBy("startedAtMs", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get().await().documents
                .mapNotNull { doc ->
                    doc.getString("json")?.let {
                        runCatching {
                            json.decodeFromString(SessionRecord.serializer(), it)
                        }.getOrNull()
                    }
                }
        }.onFailure { Log.w(TAG, "履歴の取得に失敗", it) }.getOrNull()
    }

    suspend fun getSettings(): SettingsDocument? {
        val uid = uid() ?: return null
        val ref = userDoc(uid)?.collection("settings")?.document("current") ?: return null
        return runCatching {
            ref.get().await().getString("json")
                ?.let { json.decodeFromString(SettingsDocument.serializer(), it) }
        }.onFailure { Log.w(TAG, "設定の取得に失敗", it) }.getOrNull()
    }

    /**
     * LWW で設定を書く。Rules が「updatedAtMs が現値より大」のときだけ通すため、
     * 書き込み拒否 = 自分が古い → サーバ値を読み直して呼び出し側に返す。
     * 通信失敗時は null。
     */
    suspend fun putSettings(doc: SettingsDocument): SettingsDocument? {
        val uid = uid() ?: return null
        val ref = userDoc(uid)?.collection("settings")?.document("current") ?: return null
        val putResult = runCatching {
            ref.set(
                mapOf(
                    "updatedAtMs" to doc.updatedAtMs,
                    "json" to json.encodeToString(SettingsDocument.serializer(), doc),
                ),
            ).await()
        }
        if (putResult.isSuccess) return doc
        Log.i(TAG, "設定の書き込み失敗 (LWW 拒否含む) → サーバ値を読み直す", putResult.exceptionOrNull())
        return runCatching {
            ref.get().await().getString("json")
                ?.let { json.decodeFromString(SettingsDocument.serializer(), it) }
        }.onFailure { Log.w(TAG, "サーバ設定の読み直しに失敗", it) }.getOrNull()
    }

    /**
     * Health Connect から取り込んだ 1 日分を upsert する。doc id を `record.date` 固定に
     * することで「同じ日付の上書き」になり、初回 back-fill と日次同期で重複が出ない。
     *
     * `record.isEmpty` (= `date` 以外全 null) のときは書き込まない。HC が一時的に応答を
     * 返さない瞬間に sync が走ると、過去に正しく入っていた行を null で潰してしまうため。
     */
    suspend fun upsertDailyHealth(record: DailyHealthRecord): Boolean {
        if (record.isEmpty) {
            Log.i(TAG, "dailyMetrics 空レコードは skip: ${record.date}")
            return true
        }
        val uid = uid() ?: return false
        val ref = userDoc(uid)?.collection("dailyMetrics")?.document(record.date) ?: return false
        return runCatching {
            ref.set(
                mapOf(
                    "date" to record.date,
                    "json" to json.encodeToString(DailyHealthRecord.serializer(), record),
                ),
            ).await()
            true
        }.onFailure { Log.w(TAG, "dailyMetrics の upsert に失敗: ${record.date}", it) }
            .getOrDefault(false)
    }

    /** [from] 〜 [to] (inclusive, ISO YYYY-MM-DD) の dailyMetrics を取得 */
    suspend fun listDailyHealth(from: String, to: String): List<DailyHealthRecord>? {
        val uid = uid() ?: return null
        val col = userDoc(uid)?.collection("dailyMetrics") ?: return null
        return runCatching {
            col.whereGreaterThanOrEqualTo("date", from)
                .whereLessThanOrEqualTo("date", to)
                .get().await().documents
                .mapNotNull { doc ->
                    doc.getString("json")?.let {
                        runCatching {
                            json.decodeFromString(DailyHealthRecord.serializer(), it)
                        }.getOrNull()
                    }
                }
        }.onFailure { Log.w(TAG, "dailyMetrics の取得に失敗", it) }.getOrNull()
    }
}

package io.github.wakuwaku3.adaptivepulse.mobile.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.core.sync.SyncPaths
import io.github.wakuwaku3.adaptivepulse.mobile.library.PhoneLibraryRepository
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import java.io.File
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"
private const val MAX_PENDING = 500

/** watch から受信して Firestore 未反映のセッション記録の永続キュー */
class PendingSessionStore(context: Context) {

    private val dir = File(context.filesDir, "pending-sessions").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun save(record: SessionRecord) {
        File(dir, "${record.id}.json")
            .writeText(json.encodeToString(SessionRecord.serializer(), record))
        dir.listFiles().orEmpty().sortedByDescending { it.name }
            .drop(MAX_PENDING).forEach { it.delete() }
    }

    fun list(): List<SessionRecord> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching {
                json.decodeFromString(SessionRecord.serializer(), file.readText())
            }.getOrNull()
        }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}

/** phone を起点とした同期処理 (watch ⇄ phone ⇄ Firestore)。docs/stock/sync.md */
object PhoneSync {

    val json = Json { ignoreUnknownKeys = true }

    /** 未反映キューを Firestore に送る。返り値は残件数 */
    suspend fun syncPendingSessions(context: Context): Int {
        val store = PendingSessionStore(context)
        var remaining = 0
        store.list().forEach { record ->
            if (FirestoreSync.uploadSession(record)) {
                store.delete(record.id)
            } else {
                remaining++
            }
        }
        return remaining
    }

    /**
     * 設定を Firestore と突き合わせる (LWW)。
     * ローカルが新しければ送り、サーバーが新しければ取り込んで watch にも流す。
     */
    suspend fun reconcileSettings(context: Context) {
        val repo = PhoneSettingsRepository(context)
        val local = repo.loadDocument()
        val server = FirestoreSync.getSettings()

        when {
            server == null || local.updatedAtMs > server.updatedAtMs -> {
                if (local.updatedAtMs > 0) {
                    FirestoreSync.putSettings(local)?.let { repo.replaceIfNewer(it) }
                }
            }
            else -> {
                if (repo.replaceIfNewer(server)) {
                    putSettingsToWatch(context, server)
                }
            }
        }
    }

    /** phone UI 起点の設定変更: ローカル保存 → watch へ → Firestore へ */
    suspend fun updateSettingsEverywhere(
        context: Context,
        transform: (SessionConfig) -> SessionConfig,
    ) {
        val doc = PhoneSettingsRepository(context).update(transform)
        putSettingsToWatch(context, doc)
        FirestoreSync.putSettings(doc)
    }

    suspend fun putSettingsToWatch(context: Context, doc: SettingsDocument) {
        runCatching {
            val request = PutDataRequest.create(SyncPaths.SETTINGS).apply {
                data = json.encodeToString(SettingsDocument.serializer(), doc)
                    .toByteArray(Charsets.UTF_8)
                setUrgent()
            }
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "watch への設定送信に失敗", it) }
    }

    /**
     * ライブラリを Firestore と突き合わせる (LWW)。settings と同じ規約。
     */
    suspend fun reconcileLibrary(context: Context) {
        val repo = PhoneLibraryRepository(context)
        val local = repo.loadDocumentOrNull()
        val server = FirestoreSync.getLibrary()

        when {
            server == null || (local != null && local.updatedAtMs > server.updatedAtMs) -> {
                if (local != null && local.updatedAtMs > 0) {
                    FirestoreSync.putLibrary(local)?.let { repo.replaceIfNewer(it) }
                }
            }
            else -> {
                if (repo.replaceIfNewer(server)) {
                    putLibraryToWatch(context, server)
                }
            }
        }
    }

    /** phone UI 起点のライブラリ変更: ローカル保存 → watch へ → Firestore へ */
    suspend fun updateLibraryEverywhere(
        context: Context,
        config: SessionConfig,
        transform: (LibraryDocument) -> LibraryDocument,
    ): LibraryDocument {
        val doc = PhoneLibraryRepository(context).update(config, transform)
        putLibraryToWatch(context, doc)
        FirestoreSync.putLibrary(doc)
        return doc
    }

    suspend fun putLibraryToWatch(context: Context, doc: LibraryDocument) {
        runCatching {
            val request = PutDataRequest.create(SyncPaths.LIBRARY).apply {
                data = json.encodeToString(LibraryDocument.serializer(), doc)
                    .toByteArray(Charsets.UTF_8)
                setUrgent()
            }
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onFailure { Log.w(TAG, "watch へのライブラリ送信に失敗", it) }
    }
}

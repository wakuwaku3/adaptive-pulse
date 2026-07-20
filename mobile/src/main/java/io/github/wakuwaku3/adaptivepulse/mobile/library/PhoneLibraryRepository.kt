package io.github.wakuwaku3.adaptivepulse.mobile.library

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"
private const val DEVICE = "phone"

private val Context.phoneLibraryStore by preferencesDataStore(name = "library")

/**
 * phone 側のメニュー/プログラムライブラリ。watch (Data Layer) とサーバーの両方と
 * settings と同じ LWW で同期する。未保存 (移行前) は null を返し、呼び出し側が
 * `LibraryDocument.initialFrom(config)` で hiit 移行の初期ライブラリを導出する。
 */
class PhoneLibraryRepository(private val context: Context) {

    private object Keys {
        val Document = stringPreferencesKey("library_document")
        val UpdatedAtMs = longPreferencesKey("updated_at_ms")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val stored: Flow<LibraryDocument?> = context.phoneLibraryStore.data.map { prefs ->
        prefs[Keys.Document]?.let {
            runCatching { json.decodeFromString(LibraryDocument.serializer(), it) }
                .onFailure { e -> Log.w(TAG, "保存ライブラリが不正なため無視", e) }
                .getOrNull()
        }
    }

    suspend fun load(config: SessionConfig): LibraryDocument =
        stored.first() ?: LibraryDocument.initialFrom(config)

    /** ローカル (phone UI) 起点の変更。updatedAtMs を刻印した同期用ドキュメントを返す */
    suspend fun update(
        config: SessionConfig,
        transform: (LibraryDocument) -> LibraryDocument,
    ): LibraryDocument {
        val updated = transform(load(config)).copy(
            updatedAtMs = System.currentTimeMillis(),
            updatedBy = DEVICE,
        )
        write(updated)
        return updated
    }

    /** リモート (watch / server) 起点の変更。より新しいときだけ適用 (LWW) */
    suspend fun replaceIfNewer(doc: LibraryDocument): Boolean {
        var applied = false
        context.phoneLibraryStore.edit { prefs ->
            if (doc.updatedAtMs <= (prefs[Keys.UpdatedAtMs] ?: 0L)) return@edit
            prefs[Keys.Document] = json.encodeToString(LibraryDocument.serializer(), doc)
            prefs[Keys.UpdatedAtMs] = doc.updatedAtMs
            applied = true
        }
        return applied
    }

    suspend fun loadDocumentOrNull(): LibraryDocument? = stored.first()

    private suspend fun write(doc: LibraryDocument) {
        context.phoneLibraryStore.edit { prefs ->
            prefs[Keys.Document] = json.encodeToString(LibraryDocument.serializer(), doc)
            prefs[Keys.UpdatedAtMs] = doc.updatedAtMs
        }
    }
}

package io.github.wakuwaku3.adaptivepulse.mobile.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"
private const val DEVICE = "phone"

private val Context.phoneSettingsStore by preferencesDataStore(name = "settings")

/**
 * phone 側の設定キャッシュ。watch (Data Layer) とサーバーの両方と LWW で同期する。
 * 構造は SettingsDocument の JSON 1 つで持つ (watch 側と違い表示用途が主のため)。
 */
class PhoneSettingsRepository(private val context: Context) {

    private object Keys {
        val Document = stringPreferencesKey("settings_document")
        val UpdatedAtMs = longPreferencesKey("updated_at_ms")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val document: Flow<SettingsDocument> = context.phoneSettingsStore.data.map { prefs ->
        prefs[Keys.Document]?.let {
            runCatching { json.decodeFromString(SettingsDocument.serializer(), it) }.getOrNull()
        } ?: SettingsDocument.from(SessionConfig(), updatedAtMs = 0, updatedBy = DEVICE)
    }

    suspend fun loadDocument(): SettingsDocument = document.first()

    /** ローカル (phone UI) 起点の変更 */
    suspend fun update(transform: (SessionConfig) -> SessionConfig): SettingsDocument {
        val current = loadDocument()
        val updated = SettingsDocument.from(
            config = transform(current.toSessionConfig()),
            updatedAtMs = System.currentTimeMillis(),
            updatedBy = DEVICE,
        )
        write(updated)
        return updated
    }

    /** リモート (watch / server) 起点の変更。より新しいときだけ適用 (LWW) */
    suspend fun replaceIfNewer(doc: SettingsDocument): Boolean {
        val current = loadDocument()
        if (doc.updatedAtMs <= current.updatedAtMs) return false
        if (runCatching { doc.toSessionConfig() }.isFailure) {
            Log.w(TAG, "リモート設定が不正なため無視: $doc")
            return false
        }
        write(doc)
        return true
    }

    private suspend fun write(doc: SettingsDocument) {
        context.phoneSettingsStore.edit { prefs ->
            prefs[Keys.Document] = json.encodeToString(SettingsDocument.serializer(), doc)
            prefs[Keys.UpdatedAtMs] = doc.updatedAtMs
        }
    }
}

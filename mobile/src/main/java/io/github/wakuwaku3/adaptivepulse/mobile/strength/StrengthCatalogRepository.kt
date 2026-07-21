package io.github.wakuwaku3.adaptivepulse.mobile.strength

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wakuwaku3.adaptivepulse.core.strength.StrengthCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"
private const val DEVICE = "phone"

private val Context.strengthCatalogStore by preferencesDataStore(name = "strength_catalog")

/**
 * ジム/トレーニング台帳の永続化。phone ローカルが唯一の正で、Firestore へは
 * 分析用バックアップとして送るだけ (読み戻さない) なので、settings/library と
 * 違い LWW の受信側 (replaceIfNewer) を持たない。
 */
class StrengthCatalogRepository(private val context: Context) {

    private object Keys {
        val Document = stringPreferencesKey("catalog_document")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val stored: Flow<StrengthCatalog?> = context.strengthCatalogStore.data.map { prefs ->
        prefs[Keys.Document]?.let {
            runCatching { json.decodeFromString(StrengthCatalog.serializer(), it) }
                .onFailure { e -> Log.w(TAG, "保存カタログが不正なため無視", e) }
                .getOrNull()
        }
    }

    suspend fun load(): StrengthCatalog =
        stored.first() ?: StrengthCatalog(updatedAtMs = 0, updatedBy = DEVICE)

    /**
     * phone UI 起点の変更。transform が null を返したら (一意性違反等) 何も書かず null を返す。
     * updatedAtMs / updatedBy はここで刻印し、core の ops を時計から切り離す。
     */
    suspend fun update(transform: (StrengthCatalog) -> StrengthCatalog?): StrengthCatalog? {
        val updated = transform(load())
            ?.copy(updatedAtMs = System.currentTimeMillis(), updatedBy = DEVICE)
            ?: return null
        context.strengthCatalogStore.edit { prefs ->
            prefs[Keys.Document] = json.encodeToString(StrengthCatalog.serializer(), updated)
        }
        return updated
    }
}

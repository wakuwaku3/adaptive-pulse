package io.github.wakuwaku3.adaptivepulse.library

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
private const val DEVICE = "watch"

private val Context.libraryDataStore by preferencesDataStore(name = "library")

/**
 * カスタムメニュー/プログラムと選択状態 (LibraryDocument) の永続化。
 * 文書全体を JSON 1 本で持ち、settings と同じ LWW (updatedAtMs) で phone と同期する。
 *
 * 一度も書き込まれていない間は null を返し、呼び出し側が既存の単一設定から
 * `LibraryDocument.initialFrom` で生成する (hiit 移行)。生成結果は保存しないため、
 * 最初の実編集 (選択変更・phone でのメニュー編集) までは設定変更が hiit に追従する。
 */
class LibraryRepository(private val context: Context) {

    private object Keys {
        val Json = stringPreferencesKey("json")
        val UpdatedAtMs = longPreferencesKey("updated_at_ms")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 保存済みライブラリ。未保存 (移行前) は null */
    val stored: Flow<LibraryDocument?> = context.libraryDataStore.data.map { prefs ->
        prefs[Keys.Json]?.let { raw ->
            runCatching { json.decodeFromString(LibraryDocument.serializer(), raw) }
                .onFailure { Log.w(TAG, "保存ライブラリが不正なため無視", it) }
                .getOrNull()
        }
    }

    /** 保存済みがあればそれ、無ければ既存設定から hiit 移行した初期ライブラリ */
    suspend fun load(config: SessionConfig): LibraryDocument =
        stored.first() ?: LibraryDocument.initialFrom(config)

    /** ローカル起点の変更。updatedAtMs を刻印した同期用ドキュメントを返す */
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

    /** リモート起点の変更。より新しいときだけ適用する (LWW)。適用したら true */
    suspend fun replaceIfNewer(doc: LibraryDocument): Boolean {
        var applied = false
        context.libraryDataStore.edit { prefs ->
            if (doc.updatedAtMs <= (prefs[Keys.UpdatedAtMs] ?: 0L)) return@edit
            prefs[Keys.Json] = json.encodeToString(LibraryDocument.serializer(), doc)
            prefs[Keys.UpdatedAtMs] = doc.updatedAtMs
            applied = true
        }
        return applied
    }

    private suspend fun write(doc: LibraryDocument) {
        context.libraryDataStore.edit { prefs ->
            prefs[Keys.Json] = json.encodeToString(LibraryDocument.serializer(), doc)
            prefs[Keys.UpdatedAtMs] = doc.updatedAtMs
        }
    }
}

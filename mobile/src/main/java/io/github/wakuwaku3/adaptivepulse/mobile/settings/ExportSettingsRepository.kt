package io.github.wakuwaku3.adaptivepulse.mobile.settings

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** エクスポート先の保存場所とファイル名。端末ローカルの物理パスなので同期 (SettingsDocument) には載せない */
data class ExportLocation(
    /** 共有ストレージ内の相対ディレクトリ (MediaStore RELATIVE_PATH)。既定は端末の Download 直下 */
    val relativePath: String,
    val fileName: String,
) {
    companion object {
        val DEFAULT = ExportLocation(
            relativePath = Environment.DIRECTORY_DOWNLOADS,
            fileName = "adaptive-pulse.json",
        )
    }
}

private val Context.exportSettingsStore by preferencesDataStore(name = "export")

/**
 * 自動エクスポートの保存先設定。デフォルトは `Download/adaptive-pulse.json`。
 * ミュータブル運用 (常に最新 1 枚を上書き) なのでファイル名は固定名にする。
 */
class ExportSettingsRepository(private val context: Context) {

    private object Keys {
        val RelativePath = stringPreferencesKey("relative_path")
        val FileName = stringPreferencesKey("file_name")
    }

    val location: Flow<ExportLocation> = context.exportSettingsStore.data.map { prefs ->
        ExportLocation(
            relativePath = prefs[Keys.RelativePath]?.takeIf { it.isNotBlank() }
                ?: ExportLocation.DEFAULT.relativePath,
            fileName = prefs[Keys.FileName]?.takeIf { it.isNotBlank() }
                ?: ExportLocation.DEFAULT.fileName,
        )
    }

    suspend fun load(): ExportLocation = location.first()

    suspend fun setRelativePath(path: String) {
        context.exportSettingsStore.edit { it[Keys.RelativePath] = normalizePath(path) }
    }

    suspend fun setFileName(name: String) {
        context.exportSettingsStore.edit { it[Keys.FileName] = normalizeFileName(name) }
    }

    private fun normalizePath(path: String): String =
        path.trim().trim('/').ifBlank { ExportLocation.DEFAULT.relativePath }

    /** MediaStore の DISPLAY_NAME 用に区切り文字を除き、.json 拡張子を保証する */
    private fun normalizeFileName(name: String): String {
        val base = name.trim().substringAfterLast('/').ifBlank { ExportLocation.DEFAULT.fileName }
        return if (base.endsWith(".json", ignoreCase = true)) base else "$base.json"
    }
}

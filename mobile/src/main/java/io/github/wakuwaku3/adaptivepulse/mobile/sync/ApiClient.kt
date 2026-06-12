package io.github.wakuwaku3.adaptivepulse.mobile.sync

import android.util.Log
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.github.wakuwaku3.adaptivepulse.mobile.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val TAG = "AdaptivePulse"

/**
 * 同期 API クライアント (docs/stock/sync.md)。サーバー URL 未設定なら全て null を返し、
 * 呼び出し側は「未同期のまま保留」として扱う。
 */
object ApiClient {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun baseUrl(): String? = BuildConfig.SERVER_BASE_URL.ifBlank { null }

    suspend fun uploadSession(token: String, record: SessionRecord): Boolean {
        val base = baseUrl() ?: return false
        return runCatching {
            val res = client.put("$base/v1/sessions/${record.id}") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(record)
            }
            res.status == HttpStatusCode.NoContent
        }.onFailure { Log.w(TAG, "セッションのアップロードに失敗: ${record.id}", it) }
            .getOrDefault(false)
    }

    suspend fun listSessions(token: String, limit: Int = 100): List<SessionRecord>? {
        val base = baseUrl() ?: return null
        return runCatching {
            client.get("$base/v1/sessions?limit=$limit") { bearerAuth(token) }
                .body<List<SessionRecord>>()
        }.onFailure { Log.w(TAG, "履歴の取得に失敗", it) }.getOrNull()
    }

    suspend fun getSettings(token: String): SettingsDocument? {
        val base = baseUrl() ?: return null
        return runCatching {
            val res = client.get("$base/v1/settings") { bearerAuth(token) }
            if (res.status == HttpStatusCode.NotFound) null else res.body<SettingsDocument>()
        }.onFailure { Log.w(TAG, "設定の取得に失敗", it) }.getOrNull()
    }

    /** LWW 規約: サーバーは常に正本を返す。戻り値 null は通信失敗/未設定 */
    suspend fun putSettings(token: String, doc: SettingsDocument): SettingsDocument? {
        val base = baseUrl() ?: return null
        return runCatching {
            client.put("$base/v1/settings") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(doc)
            }.body<SettingsDocument>()
        }.onFailure { Log.w(TAG, "設定の送信に失敗", it) }.getOrNull()
    }
}

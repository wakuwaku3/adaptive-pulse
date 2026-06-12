package io.github.wakuwaku3.adaptivepulse.server

import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionConfigSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class ApiTest {

    // テスト用: トークン文字列 "token-<uid>" をそのまま uid とみなす
    private val fakeVerifier = TokenVerifier { token ->
        token.removePrefix("token-").takeIf { it != token }?.let { AuthUser(it, "google.com") }
    }

    private fun record(id: String, startedAtMs: Long) = SessionRecord(
        id = id,
        startedAtMs = startedAtMs,
        durationSec = 134,
        cycles = 7,
        plannedCycles = 7,
        fatigueBrake = false,
        config = SessionConfigSnapshot.from(SessionConfig()),
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun withApi(
        store: SyncStore = InMemorySyncStore(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        application { syncApi(fakeVerifier, store) }
        block(jsonClient())
    }

    @Test
    fun `トークン無しは 401`() = withApi { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sessions").status)
    }

    @Test
    fun `不正トークンは 401`() = withApi { client ->
        val res = client.get("/v1/sessions") { bearerAuth("garbage") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `セッションを upsert して新しい順に一覧できる - ユーザーは分離される`() = withApi { client ->
        suspend fun putSession(uid: String, rec: SessionRecord) {
            val res = client.put("/v1/sessions/${rec.id}") {
                bearerAuth("token-$uid")
                contentType(ContentType.Application.Json)
                setBody(rec)
            }
            assertEquals(HttpStatusCode.NoContent, res.status)
        }
        putSession("alice", record("a-1", startedAtMs = 100))
        putSession("alice", record("a-2", startedAtMs = 200))
        putSession("alice", record("a-2", startedAtMs = 200)) // 再送 (冪等)
        putSession("bob", record("b-1", startedAtMs = 300))

        val alice: List<SessionRecord> =
            client.get("/v1/sessions") { bearerAuth("token-alice") }.body()
        assertEquals(listOf("a-2", "a-1"), alice.map { it.id })

        val bob: List<SessionRecord> =
            client.get("/v1/sessions") { bearerAuth("token-bob") }.body()
        assertEquals(listOf("b-1"), bob.map { it.id })
    }

    @Test
    fun `body とパスの id 不一致は 400`() = withApi { client ->
        val res = client.put("/v1/sessions/other-id") {
            bearerAuth("token-alice")
            contentType(ContentType.Application.Json)
            setBody(record("a-1", 100))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `設定は未登録なら 404 - LWW で古い更新は反映されない`() = withApi { client ->
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/settings") { bearerAuth("token-alice") }.status,
        )

        suspend fun putSettings(doc: SettingsDocument): SettingsDocument =
            client.put("/v1/settings") {
                bearerAuth("token-alice")
                contentType(ContentType.Application.Json)
                setBody(doc)
            }.body()

        val newer = SettingsDocument.from(
            SessionConfig(upperBpm = 150),
            updatedAtMs = 200,
            updatedBy = "phone",
        )
        assertEquals(newer, putSettings(newer))

        // 古い更新 (updatedAtMs=100) は無視され、正本が返る
        val older = SettingsDocument.from(
            SessionConfig(upperBpm = 145, lowerBpm = 120),
            updatedAtMs = 100,
            updatedBy = "watch",
        )
        assertEquals(newer, putSettings(older))

        val current: SettingsDocument =
            client.get("/v1/settings") { bearerAuth("token-alice") }.body()
        assertEquals(150, current.upperBpm)
    }

    @Test
    fun `認証プロバイダがユーザープロファイルに記録される`() {
        val store = InMemorySyncStore()
        withApi(store) { client ->
            client.put("/v1/sessions/a-1") {
                bearerAuth("token-alice")
                contentType(ContentType.Application.Json)
                setBody(record("a-1", 100))
            }
            assertEquals<Set<String>?>(setOf("google.com"), store.users["alice"])
        }
    }
}

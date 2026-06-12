package io.github.wakuwaku3.adaptivepulse.server

import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * 同期 API (docs/stock/sync.md)。すべて Firebase ID トークン必須で、
 * uid はトークンから取りパスに含めない (他人のデータに到達する経路を作らない)。
 */
fun Application.syncApi(verifier: TokenVerifier, store: SyncStore) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(Authentication) {
        bearer("firebase") {
            authenticate { credential -> verifier.verify(credential.token) }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        authenticate("firebase") {
            put("/v1/sessions/{id}") {
                val user = call.principal<AuthUser>()!!
                val record = call.receive<SessionRecord>()
                if (record.id != call.parameters["id"]) {
                    call.respond(HttpStatusCode.BadRequest, "id mismatch")
                    return@put
                }
                store.ensureUser(user)
                store.upsertSession(user.uid, record)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/v1/sessions") {
                val user = call.principal<AuthUser>()!!
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 500) ?: 50
                call.respond(store.listSessions(user.uid, limit))
            }

            get("/v1/settings") {
                val user = call.principal<AuthUser>()!!
                val settings = store.getSettings(user.uid)
                if (settings == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(settings)
                }
            }

            put("/v1/settings") {
                val user = call.principal<AuthUser>()!!
                val doc = call.receive<SettingsDocument>()
                store.ensureUser(user)
                call.respond(store.putSettingsIfNewer(user.uid, doc))
            }
        }
    }
}

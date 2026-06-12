package io.github.wakuwaku3.adaptivepulse.server

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** 認証済みユーザー。uid が Firestore のキー (docs/stock/sync.md) */
data class AuthUser(
    val uid: String,
    /** 例 "google.com"。将来の認証手段追加に備えてデータ側にも残す */
    val provider: String?,
)

/** ID トークンの検証。テストでは fake に差し替える */
fun interface TokenVerifier {
    suspend fun verify(token: String): AuthUser?
}

/** Firebase Auth による検証 (本番経路) */
class FirebaseTokenVerifier(private val auth: FirebaseAuth) : TokenVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun verify(token: String): AuthUser? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = auth.verifyIdToken(token)
            @Suppress("UNCHECKED_CAST")
            val firebaseClaim = decoded.claims["firebase"] as? Map<String, Any?>
            AuthUser(
                uid = decoded.uid,
                provider = firebaseClaim?.get("sign_in_provider") as? String,
            )
        }.onFailure { log.info("ID トークン検証に失敗: ${it.message}") }.getOrNull()
    }
}

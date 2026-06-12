package io.github.wakuwaku3.adaptivepulse.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.cloud.FirestoreClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * 本番エントリポイント。Cloud Run を想定し PORT 環境変数で待ち受ける。
 * 資格情報は Application Default Credentials (Cloud Run のサービスアカウント、
 * ローカルは GOOGLE_APPLICATION_CREDENTIALS) で解決する。
 */
fun main() {
    val firebase = FirebaseApp.initializeApp(
        FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .apply { System.getenv("FIREBASE_PROJECT_ID")?.let(::setProjectId) }
            .build(),
    )
    val verifier = FirebaseTokenVerifier(FirebaseAuth.getInstance(firebase))
    val store = FirestoreSyncStore(FirestoreClient.getFirestore(firebase))

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) { syncApi(verifier, store) }
        .start(wait = true)
}

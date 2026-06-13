package io.github.wakuwaku3.adaptivepulse.mobile.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import kotlinx.coroutines.tasks.await

/**
 * Firebase Auth (Google サインイン)。プロバイダ追加は Firebase の account linking で
 * 同一 uid に集約されるため、ここに増やしていく (docs/stock/sync.md)。
 * google-services.json 未配置でもアプリ自体は動く (isConfigured = false)。
 */
class AuthManager(private val context: Context) {

    /** Firebase プロジェクト設定 (google-services.json) が組み込まれているか */
    val isConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    val currentUser: FirebaseUser?
        get() = if (isConfigured) FirebaseAuth.getInstance().currentUser else null

    suspend fun signInWithGoogle(activity: Activity): Result<FirebaseUser> = runCatching {
        // default_web_client_id は google-services プラグインが生成するリソース。
        // 直接 R 参照すると未配置環境でビルドできないため動的に解決する
        val webClientIdRes = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName,
        )
        check(webClientIdRes != 0) { "Firebase 未設定 (google-services.json を配置してください)" }

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(context.getString(webClientIdRes))
                    .setFilterByAuthorizedAccounts(false)
                    .build(),
            )
            .build()
        val credential = CredentialManager.create(context)
            .getCredential(activity, request).credential
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken

        val user = FirebaseAuth.getInstance()
            .signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null))
            .await()
            .user ?: error("サインイン結果にユーザーがいません")
        // users/{uid} のプロバイダ列を Firestore に記録 (server 廃止後はクライアント主導)
        FirestoreSync.ensureUser("google.com")
        user
    }

    fun signOut() {
        if (isConfigured) FirebaseAuth.getInstance().signOut()
    }
}

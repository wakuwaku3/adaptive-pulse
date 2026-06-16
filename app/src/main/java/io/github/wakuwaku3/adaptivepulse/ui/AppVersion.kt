package io.github.wakuwaku3.adaptivepulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** sideload した APK の versionName を画面に出すための helper (どのビルドが入っているかの確認用) */
@Composable
fun appVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrElse { "?" }
    }
}

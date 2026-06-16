package io.github.wakuwaku3.adaptivepulse.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * このコンポーザブルが Composition 中の間、画面オフを抑止する。
 * セッション中はジムで腕を振りながら使うため、画面オフ中に Health Services の
 * サンプリングが落ちる挙動を避けたい (実機 FB)。
 */
@Composable
fun KeepScreenOn() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

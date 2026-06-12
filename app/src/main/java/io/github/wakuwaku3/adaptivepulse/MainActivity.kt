package io.github.wakuwaku3.adaptivepulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.SwipeToDismissBox
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.session.SessionScreen
import io.github.wakuwaku3.adaptivepulse.session.SessionService
import io.github.wakuwaku3.adaptivepulse.settings.SettingEditorScreen
import io.github.wakuwaku3.adaptivepulse.settings.SettingItem
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import io.github.wakuwaku3.adaptivepulse.settings.SettingsScreen
import io.github.wakuwaku3.adaptivepulse.sync.updateSettingsAndSync
import io.github.wakuwaku3.adaptivepulse.ui.theme.AdaptivePulseTheme
import kotlinx.coroutines.launch

private sealed interface AppScreen {
    data object Session : AppScreen

    data object Settings : AppScreen

    data class Editor(val item: SettingItem) : AppScreen
}

class MainActivity : ComponentActivity() {

    // 拒否されても開始する (心拍は合成ソースに、通知は非表示になるだけ)
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            SessionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // タイルからの起動なら即セッション開始 (1 スワイプ + 1 タップで計測が始まる)。
        // 再生成時の重複は SessionService 側の二重開始ガードが吸収する
        if (intent?.getBooleanExtra(EXTRA_START_SESSION, false) == true) {
            startWithPermissions()
        }
        setContent {
            AdaptivePulseTheme {
                AppNavigation()
            }
        }
    }

    companion object {
        const val EXTRA_START_SESSION = "start_session"
    }

    @Composable
    private fun AppNavigation() {
        val sessionState by SessionService.state.collectAsState()
        val settings = remember { SettingsRepository(applicationContext) }
        val config by settings.config.collectAsState(initial = SessionConfig())
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf<AppScreen>(AppScreen.Session) }

        when (val current = screen) {
            AppScreen.Session -> SessionScreen(
                state = sessionState,
                onStart = ::startWithPermissions,
                onStop = { SessionService.stop(this) },
                onOpenSettings = { screen = AppScreen.Settings },
            )

            AppScreen.Settings -> SwipeToDismissBox(
                onDismissed = { screen = AppScreen.Session },
            ) { isBackground ->
                if (!isBackground) {
                    SettingsScreen(
                        config = config,
                        onSelect = { screen = AppScreen.Editor(it) },
                        onBack = { screen = AppScreen.Session },
                    )
                }
            }

            is AppScreen.Editor -> SwipeToDismissBox(
                onDismissed = { screen = AppScreen.Settings },
            ) { isBackground ->
                if (!isBackground) {
                    SettingEditorScreen(
                        item = current.item,
                        config = config,
                        onChange = { updated ->
                            scope.launch { updateSettingsAndSync(applicationContext) { updated } }
                        },
                        onBack = { screen = AppScreen.Settings },
                    )
                }
            }
        }
    }

    private fun startWithPermissions() {
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION) // カロリー取得用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            SessionService.start(this)
        } else {
            requestPermissions.launch(needed.toTypedArray())
        }
    }
}

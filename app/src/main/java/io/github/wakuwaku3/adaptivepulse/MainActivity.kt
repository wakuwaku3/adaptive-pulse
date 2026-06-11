package io.github.wakuwaku3.adaptivepulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.wakuwaku3.adaptivepulse.session.SessionScreen
import io.github.wakuwaku3.adaptivepulse.session.SessionService
import io.github.wakuwaku3.adaptivepulse.ui.theme.AdaptivePulseTheme

class MainActivity : ComponentActivity() {

    // 拒否されても開始する (心拍は合成ソースに、通知は非表示になるだけ)
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            SessionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdaptivePulseTheme {
                val state by SessionService.state.collectAsState()
                SessionScreen(
                    state = state,
                    onStart = ::startWithPermissions,
                    onStop = { SessionService.stop(this) },
                )
            }
        }
    }

    private fun startWithPermissions() {
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
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

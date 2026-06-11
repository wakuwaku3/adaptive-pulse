package io.github.wakuwaku3.adaptivepulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.github.wakuwaku3.adaptivepulse.hr.AutoHeartRateSource
import io.github.wakuwaku3.adaptivepulse.session.SessionScreen
import io.github.wakuwaku3.adaptivepulse.session.SessionViewModel
import io.github.wakuwaku3.adaptivepulse.session.SessionVibrator
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import io.github.wakuwaku3.adaptivepulse.ui.theme.AdaptivePulseTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels {
        val settings = SettingsRepository(applicationContext)
        SessionViewModel.factory(
            vibrator = SessionVibrator.from(applicationContext),
            configProvider = settings::load,
            sourceFactory = { phaseProvider ->
                AutoHeartRateSource(applicationContext, phaseProvider)
            },
        )
    }

    // 拒否されても開始する (AutoHeartRateSource が合成ソースに切り替える)
    private val requestBodySensors =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdaptivePulseTheme {
                SessionScreen(viewModel, onStart = ::startWithPermission)
            }
        }
    }

    private fun startWithPermission() {
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start()
        } else {
            requestBodySensors.launch(Manifest.permission.BODY_SENSORS)
        }
    }
}

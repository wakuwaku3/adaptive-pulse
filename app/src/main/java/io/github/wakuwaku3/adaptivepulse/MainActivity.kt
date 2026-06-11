package io.github.wakuwaku3.adaptivepulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.wakuwaku3.adaptivepulse.session.SessionScreen
import io.github.wakuwaku3.adaptivepulse.session.SessionViewModel
import io.github.wakuwaku3.adaptivepulse.session.SessionVibrator
import androidx.wear.compose.material.MaterialTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SessionViewModel by viewModels {
        SessionViewModel.factory(SessionVibrator.from(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SessionScreen(viewModel)
            }
        }
    }
}

package io.github.wakuwaku3.adaptivepulse.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import io.github.wakuwaku3.adaptivepulse.MainActivity
import io.github.wakuwaku3.adaptivepulse.R
import io.github.wakuwaku3.adaptivepulse.hr.AutoHeartRateSource
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "AdaptivePulse"
private const val CHANNEL_ID = "session"
private const val NOTIFICATION_ID = 1

/**
 * セッションの実行主体。Foreground Service として動くことで画面オフ中も
 * 計測を継続する (要件の非機能要件)。状態は process-wide な StateFlow で
 * 公開し、UI はこれを購読するだけにする。
 */
class SessionService : LifecycleService() {

    companion object {
        private const val ACTION_STOP = "io.github.wakuwaku3.adaptivepulse.STOP_SESSION"

        private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
        val state: StateFlow<SessionUiState> = _state

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private var sessionJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (sessionJob != null) return
        goForeground()

        val vibrator = SessionVibrator.from(applicationContext)
        val settings = SettingsRepository(applicationContext)
        sessionJob = lifecycleScope.launch {
            val config = settings.load()
            Log.i(TAG, "セッション開始: $config")
            SessionRunner(
                config = config,
                sourceFactory = { phaseProvider ->
                    AutoHeartRateSource(applicationContext, phaseProvider)
                },
                onSessionEvent = vibrator::vibrate,
                onState = { _state.value = it },
            ).run()
            // 完走: 結果 (Finished) は表示し続け、サービスだけ畳む
            sessionJob = null
            stopSelf()
        }
    }

    private fun stopSession() {
        // 手動停止は結果を残さず Idle に戻す (DONE 画面の OK もここに来る)
        sessionJob?.cancel()
        sessionJob = null
        _state.value = SessionUiState.Idle
        stopSelf()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW),
        )

        val touchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentTitle("AdaptivePulse")
            .setContentText("Session running")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(touchIntent)

        // ウォッチフェイス上にセッション中インジケータを出す
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_pulse)
            .setTouchIntent(touchIntent)
            .build()
            .apply(applicationContext)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type)
    }
}

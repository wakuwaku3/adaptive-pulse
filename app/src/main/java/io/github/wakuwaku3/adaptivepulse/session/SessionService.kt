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
import io.github.wakuwaku3.adaptivepulse.core.Phase
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionConfigSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.history.WatchHistoryStore
import io.github.wakuwaku3.adaptivepulse.hr.AutoExerciseSource
import io.github.wakuwaku3.adaptivepulse.settings.SettingsRepository
import io.github.wakuwaku3.adaptivepulse.sync.WearSync
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AdaptivePulse"
private const val CHANNEL_ID = "session"
private const val NOTIFICATION_ID = 1

/** SessionUiState → 同期 DTO の変換。Idle は live 表示対象外なので null */
private fun SessionUiState.toLiveSnapshot(nowMs: Long): SessionLiveSnapshot? = when (this) {
    SessionUiState.Idle -> null
    is SessionUiState.Running -> SessionLiveSnapshot(
        updatedAtMs = nowMs,
        phase = when {
            isWarmingUp -> LivePhase.WARM_UP
            phase == Phase.HIGH_INTENSITY -> LivePhase.HIGH
            phase == Phase.RECOVERY -> LivePhase.RECOVERY
            else -> LivePhase.DONE
        },
        bpm = bpm,
        currentCycle = currentCycle,
        finalCycle = finalCycle,
        elapsedSec = elapsed.inWholeMilliseconds / 1000.0,
        cycleElapsedSec = cycleElapsed.inWholeMilliseconds / 1000.0,
        phaseElapsedSec = phaseElapsed.inWholeMilliseconds / 1000.0,
        upperBpm = upperBpm,
        lowerBpm = lowerBpm,
        calories = calories,
        currentRps = currentRps,
    )
    is SessionUiState.Finished -> SessionLiveSnapshot(
        updatedAtMs = nowMs,
        phase = LivePhase.DONE,
        bpm = null,
        currentCycle = cycles,
        finalCycle = cycles,
        elapsedSec = elapsed.inWholeMilliseconds / 1000.0,
        cycleElapsedSec = 0.0,
        phaseElapsedSec = 0.0,
        upperBpm = 0,
        lowerBpm = 0,
        calories = calories,
        currentRps = null,
    )
}

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

        // クラウン回転はミリ秒単位で連射されうるため、Intent 経由ではなく直接呼べる関数で経路化する。
        // Service 生存期間 = セッション生存期間と一致するので、開始時にセット・終了時にクリアする
        @Volatile
        private var activeAdjust: ((Int) -> Unit)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }

        /** 現フェーズが見ている閾値を delta だけ動かす (高強度=上限、回復=下限)。セッション外は no-op */
        fun adjustActiveThreshold(delta: Int) {
            activeAdjust?.invoke(delta)
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
            val startedAtMs = System.currentTimeMillis()
            // phone 側を前面化する ping (再送・永続不要なので MessageClient で 1 発)。
            // 失敗してもセッション自体には影響させない (WearSync 内で握りつぶす)
            WearSync.sendStartForeground(applicationContext)
            val livePusher = LiveSnapshotPusher(applicationContext)
            val runner = SessionRunner(
                config = config,
                sourceFactory = { phaseProvider ->
                    AutoExerciseSource(applicationContext, phaseProvider)
                },
                onSessionEvent = vibrator::vibrate,
                onState = { state ->
                    _state.value = state
                    livePusher.maybePush(state)
                },
            )
            // クラウン回転を runner に橋渡しする (振動 tap で操作を体感確認できるようにする)
            activeAdjust = { delta ->
                runner.adjustActiveThreshold(delta)
                vibrator.vibrateTap()
            }
            try {
                val result = runner.run()
                // 完走 (タイムアウトでの強制終了も含む): 結果 (Finished) は表示し続けサービスだけ畳む
                recordSession(startedAtMs, result)
            } catch (e: CancellationException) {
                // 手動停止: 1 サイクル以上完走していれば部分履歴を残す
                val snap = runner.snapshot()
                if (snap.cycles >= 1) {
                    withContext(NonCancellable) { recordSession(startedAtMs, snap) }
                }
                throw e
            } catch (e: Exception) {
                // センサー経路の失敗でアプリごと落とさない。途中までの履歴は残し、Idle で再開可能にする
                Log.e(TAG, "セッションが異常終了", e)
                withContext(NonCancellable) { recordSession(startedAtMs, runner.snapshot()) }
                _state.value = SessionUiState.Idle
            } finally {
                activeAdjust = null
                sessionJob = null
                // ライブ DataItem は phone のライブ画面を閉じるトリガー。
                // NonCancellable で確実に消す (上の catch から throw されてきてもここは通る)
                withContext(NonCancellable) { WearSync.deleteLiveSnapshot(applicationContext) }
                stopSelf()
            }
        }
    }

    /** 1Hz 程度に間引いて live snapshot を Data Layer に書く */
    private inner class LiveSnapshotPusher(private val context: Context) {
        private var lastPushAtMs: Long = 0L

        fun maybePush(state: SessionUiState) {
            val now = System.currentTimeMillis()
            // Running / Finished はライブ表示の対象。Idle は無視
            val snapshot = state.toLiveSnapshot(now) ?: return
            // 1 秒未満の連射は捨てる (tick と sample で 2 重発火するため)。
            // 終了 (DONE) は phone へ確実に届けるため間引かない
            if (snapshot.phase != LivePhase.DONE && now - lastPushAtMs < 1000L) return
            lastPushAtMs = now
            lifecycleScope.launch { WearSync.putLiveSnapshot(context, snapshot) }
        }
    }

    /** 完走セッションを履歴に残し、phone へ送る (失敗してもセッション完了は壊さない) */
    private suspend fun recordSession(startedAtMs: Long, result: SessionResult) {
        val record = SessionRecord(
            id = "$startedAtMs-${UUID.randomUUID().toString().take(8)}",
            startedAtMs = startedAtMs,
            durationSec = result.elapsed.inWholeSeconds,
            cycles = result.cycles,
            plannedCycles = result.config.targetCycles,
            fatigueBrake = result.fatigueBrake,
            calories = result.calories,
            zoneRatio = result.zoneRatio,
            highDurationsSec = result.highDurations.map { it.inWholeMilliseconds / 1000.0 },
            recoveryDurationsSec = result.recoveryDurations.map { it.inWholeMilliseconds / 1000.0 },
            avgBpm = result.avgBpm,
            maxBpm = result.maxBpm,
            config = SessionConfigSnapshot.from(result.config),
        )
        runCatching { WatchHistoryStore(applicationContext).save(record) }
            .onFailure { Log.w(TAG, "履歴の保存に失敗", it) }
        WearSync.putSession(applicationContext, record)
        Log.i(TAG, "セッションを記録: ${record.id}")
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

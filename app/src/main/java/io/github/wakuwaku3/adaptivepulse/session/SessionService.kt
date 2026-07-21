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
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.menu.Presets
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlan
import io.github.wakuwaku3.adaptivepulse.core.menu.SessionPlanner
import io.github.wakuwaku3.adaptivepulse.core.sync.LivePhase
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionConfigSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot
import io.github.wakuwaku3.adaptivepulse.core.sync.SessionRecord
import io.github.wakuwaku3.adaptivepulse.history.WatchHistoryStore
import io.github.wakuwaku3.adaptivepulse.hr.AutoExerciseSource
import io.github.wakuwaku3.adaptivepulse.library.LibraryRepository
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
        phase = phase,
        bpm = bpm,
        currentCycle = currentCycle,
        finalCycle = finalCycle,
        elapsedSec = elapsed.inWholeMilliseconds / 1000.0,
        cycleElapsedSec = cycleElapsed.inWholeMilliseconds / 1000.0,
        phaseElapsedSec = phaseElapsed.inWholeMilliseconds / 1000.0,
        upperBpm = upperBpm,
        lowerBpm = lowerBpm,
        calories = calories,
        targetCadenceHigh = targetCadenceHigh,
        targetCadenceRecovery = targetCadenceRecovery,
        suggestion = suggestion,
        menuName = menuName,
        menuIndex = menuIndex,
        menuCount = menuCount,
        timedTargetSec = timedTarget?.let { it.inWholeMilliseconds / 1000.0 },
        timedElapsedSec = if (timedTarget != null) phaseElapsed.inWholeMilliseconds / 1000.0 else null,
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
        targetCadenceHigh = 0,
        targetCadenceRecovery = 0,
        suggestion = suggestion,
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
        private const val ACTION_DONE = "io.github.wakuwaku3.adaptivepulse.DONE_SESSION"

        private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
        val state: StateFlow<SessionUiState> = _state

        // クラウン回転・phone ボタンはミリ秒単位で連射されうるため、Intent 経由ではなく直接呼べる関数で経路化する
        @Volatile
        private var activeAdjustThreshold: ((Int) -> Unit)? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        /**
         * セッション中断 (Running → Finished)。
         * live snapshot は維持して phone に「DONE 画面で Done 待ち」を見せ続ける (FB 2026-06-24)。
         */
        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }

        /**
         * Done 確認 (Finished → Idle)。live snapshot を消して phone を dashboard に戻す。
         * 「セッション終了後すぐにダッシュボードへ戻る」挙動を抑え、ユーザの確認操作を要件化する。
         */
        fun done(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_DONE),
            )
        }

        /** 現フェーズが見ている閾値を delta だけ動かす (高強度=上限、回復=下限)。セッション外は no-op */
        fun adjustActiveThreshold(delta: Int) {
            activeAdjustThreshold?.invoke(delta)
        }
    }

    private var sessionJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_DONE -> finishSession()
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
            val plan = resolvePlan(config)
            Log.i(TAG, "セッション開始: plan=${plan.name} segments=${plan.segments.size} $config")
            val startedAtMs = System.currentTimeMillis()
            WearSync.sendStartForeground(applicationContext)
            val livePusher = LiveSnapshotPusher(applicationContext)
            val runner = SessionRunner(
                plan = plan,
                config = config,
                sourceFactory = { phase ->
                    AutoExerciseSource(applicationContext, phase)
                },
                onSessionEvent = vibrator::vibrate,
                onState = { state ->
                    _state.value = state
                    livePusher.maybePush(state)
                },
            )
            activeAdjustThreshold = { delta ->
                runner.adjustActiveThreshold(delta)
                vibrator.vibrateTap()
            }
            try {
                val result = runner.run()
                recordSession(startedAtMs, result)
            } catch (e: CancellationException) {
                val snap = runner.snapshot()
                if (snap.cycles >= 1) {
                    withContext(NonCancellable) { recordSession(startedAtMs, snap) }
                }
                // ユーザ stop 経由でも Finished 画面で Done を待たせる (要件: Done を押すまで戻らない)。
                // snapshot 経由で抽出した値だけを使い、UI 側で同じ「DONE」表現に着地させる
                val finished = SessionUiState.Finished(
                    cycles = snap.cycles,
                    elapsed = snap.elapsed,
                    calories = snap.calories,
                    zoneRatio = snap.zoneRatio,
                    suggestion = runner.snapshotSuggestion(),
                )
                _state.value = finished
                withContext(NonCancellable) { livePusher.maybePush(finished) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "セッションが異常終了", e)
                val snap = runner.snapshot()
                withContext(NonCancellable) { recordSession(startedAtMs, snap) }
                // 異常終了でも phone を勝手に dashboard へ戻さず、Finished 画面で Done を待つ
                val finished = SessionUiState.Finished(
                    cycles = snap.cycles,
                    elapsed = snap.elapsed,
                    calories = snap.calories,
                    zoneRatio = snap.zoneRatio,
                    suggestion = runner.snapshotSuggestion(),
                )
                _state.value = finished
                withContext(NonCancellable) { livePusher.maybePush(finished) }
            } finally {
                activeAdjustThreshold = null
                sessionJob = null
                // live snapshot は Done が押されるまで残す。phone は DONE 画面で確認待ちにする
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

    /**
     * 開始画面の選択 (最後に使ったメニュー/プログラム) をプランに解決する。
     * 参照切れ (phone で削除された等) は hiit 移行の初期ライブラリにフォールバックする。
     */
    private suspend fun resolvePlan(config: SessionConfig): SessionPlan {
        val library = LibraryRepository(applicationContext).load(config)
        val presetMenus = Presets.menus(config.ageYears)
        SessionPlanner.resolve(library.selection, library, presetMenus)?.let { return it }
        Log.w(TAG, "選択 ${library.selection} が解決できないため hiit にフォールバック")
        val fallback = LibraryDocument.initialFrom(config)
        return SessionPlanner.resolve(fallback.selection, fallback, presetMenus)
            ?: error("hiit フォールバックは常に解決できるはず")
    }

    /** 完走セッションを履歴に残し、phone へ送る (失敗してもセッション完了は壊さない) */
    private suspend fun recordSession(startedAtMs: Long, result: SessionResult) {
        val record = SessionRecord(
            id = "$startedAtMs-${UUID.randomUUID().toString().take(8)}",
            startedAtMs = startedAtMs,
            durationSec = result.elapsed.inWholeSeconds,
            cycles = result.cycles,
            plannedCycles = result.plannedCycles,
            fatigueBrake = result.fatigueBrake,
            calories = result.calories,
            zoneRatio = result.zoneRatio,
            highDurationsSec = result.highDurations.map { it.inWholeMilliseconds / 1000.0 },
            recoveryDurationsSec = result.recoveryDurations.map { it.inWholeMilliseconds / 1000.0 },
            avgBpm = result.avgBpm,
            maxBpm = result.maxBpm,
            hrBpmBySecond = result.hrBpmBySecond.takeIf { it.isNotEmpty() },
            config = SessionConfigSnapshot.from(result.config),
            plan = result.plan,
        )
        runCatching { WatchHistoryStore(applicationContext).save(record) }
            .onFailure { Log.w(TAG, "履歴の保存に失敗", it) }
        WearSync.putSession(applicationContext, record)
        Log.i(TAG, "セッションを記録: ${record.id}")
    }

    /**
     * セッション中断: job を cancel して runner の catch 経路で Finished に遷移させる。
     * live snapshot は維持したまま service を止める (Done 確認は [finishSession] で行う)。
     * 既に Finished の状態 (自然完走 → stop が誤って 2 回押された等) で来ても、cancel は no-op。
     */
    private fun stopSession() {
        sessionJob?.cancel()
        // service 自体は job の finally から stopSelf される。ここでは何もしない
    }

    /**
     * Done 確認: live snapshot を消して状態を Idle に戻す。
     * 自然完走後・stop 後のどちらからも呼べる (sessionJob は既に null のことが多い)。
     */
    private fun finishSession() {
        sessionJob?.cancel()
        sessionJob = null
        _state.value = SessionUiState.Idle
        lifecycleScope.launch(NonCancellable) {
            WearSync.deleteLiveSnapshot(applicationContext)
            stopSelf()
        }
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

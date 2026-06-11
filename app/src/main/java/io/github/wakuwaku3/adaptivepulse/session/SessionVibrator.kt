package io.github.wakuwaku3.adaptivepulse.session

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.wakuwaku3.adaptivepulse.core.SessionEvent

/**
 * イベント→振動パターンの 1:1 対応。運動中は画面を見られないため、
 * パターンは回数と長さで体感区別できるよう設計する (実機で要調整)。
 */
class SessionVibrator(private val vibrator: Vibrator) {

    fun vibrate(event: SessionEvent) {
        // 先頭 0 は即時開始。以降 [振動, 休止, 振動, ...] (ms)
        val timings = when (event) {
            SessionEvent.EnterRecovery -> longArrayOf(0, 800) // 長 1: 減速しろ
            SessionEvent.EnterHighIntensity -> longArrayOf(0, 150, 100, 150, 100, 150) // 短 3: 加速しろ
            SessionEvent.PhaseTimeout -> longArrayOf(0, 400, 200, 400) // 中 2: 強制遷移
            SessionEvent.FatigueBrake -> longArrayOf(0, 150, 100, 150, 100, 800) // 短短長: 疲労・これが最終
            SessionEvent.SessionFinished -> longArrayOf(0, 800, 300, 800, 300, 800) // 長 3: 終了
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    companion object {
        fun from(context: Context): SessionVibrator {
            // minSdk 30 (API 30) では VibratorManager (API 31+) が使えない
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            return SessionVibrator(vibrator)
        }
    }
}

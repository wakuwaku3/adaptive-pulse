package io.github.wakuwaku3.adaptivepulse.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

private const val TAG = "AdaptivePulse"

/**
 * 開発用: adb broadcast で設定を書き換える (設定画面実装までのつなぎ + エミュレータ検証用)。
 * 例: adb shell am broadcast -a io.github.wakuwaku3.adaptivepulse.SET_CONFIG \
 *       --ei upper_bpm 148 --ei lower_bpm 120 io.github.wakuwaku3.adaptivepulse
 * debuggable ビルド以外では何もしない。
 */
class DebugConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

        // 設定書き込みは小さく即時 (debug 専用なので runBlocking を許容)
        runBlocking {
            io.github.wakuwaku3.adaptivepulse.sync.updateSettingsAndSync(context) { current ->
                current.copy(
                    upperBpm = intent.intExtra("upper_bpm", current.upperBpm),
                    lowerBpm = intent.intExtra("lower_bpm", current.lowerBpm),
                    targetCycles = intent.intExtra("target_cycles", current.targetCycles),
                    fatigueRatio = intent.getDoubleExtra("fatigue_ratio", current.fatigueRatio),
                    minBaseline = intent.secondsExtra("min_baseline_secs", current.minBaseline),
                    highPhaseTimeout =
                        intent.secondsExtra("high_timeout_secs", current.highPhaseTimeout),
                    recoveryTimeout =
                        intent.secondsExtra("recovery_timeout_secs", current.recoveryTimeout),
                )
            }
            Log.i(TAG, "設定を更新: ${SettingsRepository(context).load()}")
        }
    }

    private fun Intent.intExtra(key: String, fallback: Int): Int = getIntExtra(key, fallback)

    private fun Intent.secondsExtra(key: String, fallback: kotlin.time.Duration) =
        if (hasExtra(key)) getIntExtra(key, 0).seconds else fallback
}

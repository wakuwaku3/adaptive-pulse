package io.github.wakuwaku3.adaptivepulse.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "AdaptivePulse"

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * SessionConfig の永続化 (要件: ハードコードせず変更可能にする)。
 * 未設定の項目は SessionConfig のデフォルトを使う。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val UpperBpm = intPreferencesKey("upper_bpm")
        val LowerBpm = intPreferencesKey("lower_bpm")
        val TargetCycles = intPreferencesKey("target_cycles")
        val FatigueRatio = doublePreferencesKey("fatigue_ratio")
        val MinBaselineSecs = longPreferencesKey("min_baseline_secs")
        val HighTimeoutSecs = longPreferencesKey("high_timeout_secs")
        val RecoveryTimeoutSecs = longPreferencesKey("recovery_timeout_secs")
    }

    val config: Flow<SessionConfig> = context.settingsDataStore.data.map { it.toConfig() }

    suspend fun load(): SessionConfig = config.first()

    suspend fun update(transform: (SessionConfig) -> SessionConfig) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(prefs.toConfig())
            prefs[Keys.UpperBpm] = updated.upperBpm
            prefs[Keys.LowerBpm] = updated.lowerBpm
            prefs[Keys.TargetCycles] = updated.targetCycles
            prefs[Keys.FatigueRatio] = updated.fatigueRatio
            prefs[Keys.MinBaselineSecs] = updated.minBaseline.inWholeSeconds
            prefs[Keys.HighTimeoutSecs] = updated.highPhaseTimeout.inWholeSeconds
            prefs[Keys.RecoveryTimeoutSecs] = updated.recoveryTimeout.inWholeSeconds
        }
    }

    private fun Preferences.toConfig(): SessionConfig {
        val defaults = SessionConfig()
        return runCatching {
            SessionConfig(
                upperBpm = this[Keys.UpperBpm] ?: defaults.upperBpm,
                lowerBpm = this[Keys.LowerBpm] ?: defaults.lowerBpm,
                targetCycles = this[Keys.TargetCycles] ?: defaults.targetCycles,
                fatigueRatio = this[Keys.FatigueRatio] ?: defaults.fatigueRatio,
                minBaseline = this[Keys.MinBaselineSecs]?.seconds ?: defaults.minBaseline,
                highPhaseTimeout = this[Keys.HighTimeoutSecs]?.seconds ?: defaults.highPhaseTimeout,
                recoveryTimeout = this[Keys.RecoveryTimeoutSecs]?.seconds
                    ?: defaults.recoveryTimeout,
            )
        }.getOrElse {
            // 不正な保存値 (閾値逆転など) で起動不能にならないようデフォルトに戻す
            Log.w(TAG, "保存設定が不正なためデフォルトを使用", it)
            defaults
        }
    }
}

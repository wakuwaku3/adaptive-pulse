package io.github.wakuwaku3.adaptivepulse.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.sync.SettingsDocument
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "AdaptivePulse"

/** この端末からの設定更新であることを示す識別子 (LWW の updatedBy) */
private const val DEVICE = "watch"

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * SessionConfig の永続化 (要件: ハードコードせず変更可能にする)。
 * phone との双方向同期のため updatedAtMs/updatedBy も持ち、
 * 最終更新者勝ち (LWW) で解決する (docs/stock/sync.md)。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val UpperBpm = intPreferencesKey("upper_bpm")
        val LowerBpm = intPreferencesKey("lower_bpm")
        val TargetCycles = intPreferencesKey("target_cycles")
        val FatigueRatio = doublePreferencesKey("fatigue_ratio")
        val RecoveryFatigueRatio = doublePreferencesKey("recovery_fatigue_ratio")
        val MinBaselineSecs = longPreferencesKey("min_baseline_secs")
        val HighTimeoutSecs = longPreferencesKey("high_timeout_secs")
        val RecoveryTimeoutSecs = longPreferencesKey("recovery_timeout_secs")
        val AgeYears = intPreferencesKey("age_years")
        val RestingBpm = intPreferencesKey("resting_bpm")
        val UpdatedAtMs = longPreferencesKey("updated_at_ms")
        val UpdatedBy = stringPreferencesKey("updated_by")
        val SeedTargetCadenceHigh = doublePreferencesKey("seed_target_cadence_high")
        val SeedTargetCadenceRecovery = doublePreferencesKey("seed_target_cadence_recovery")
        val HeightCm = intPreferencesKey("height_cm")
    }

    val config: Flow<SessionConfig> = context.settingsDataStore.data.map { it.toConfig() }

    suspend fun load(): SessionConfig = config.first()

    suspend fun loadDocument(): SettingsDocument =
        context.settingsDataStore.data.first().toDocument()

    /** ローカル起点の変更。updatedAtMs を刻印した同期用ドキュメントを返す */
    suspend fun update(transform: (SessionConfig) -> SessionConfig): SettingsDocument {
        lateinit var result: SettingsDocument
        context.settingsDataStore.edit { prefs ->
            val updated = transform(prefs.toConfig())
            prefs.write(updated, updatedAtMs = System.currentTimeMillis(), updatedBy = DEVICE)
            result = prefs.toDocument()
        }
        return result
    }

    /** リモート起点の変更。より新しいときだけ適用する (LWW)。適用したら true */
    suspend fun replaceIfNewer(doc: SettingsDocument): Boolean {
        var applied = false
        context.settingsDataStore.edit { prefs ->
            if (doc.updatedAtMs > (prefs[Keys.UpdatedAtMs] ?: 0L)) {
                val config = runCatching { doc.toSessionConfig() }.getOrElse {
                    Log.w(TAG, "リモート設定が不正なため無視", it)
                    return@edit
                }
                prefs.write(config, doc.updatedAtMs, doc.updatedBy)
                applied = true
            }
        }
        return applied
    }

    private fun MutablePreferences.write(config: SessionConfig, updatedAtMs: Long, updatedBy: String) {
        this[Keys.UpperBpm] = config.upperBpm
        this[Keys.LowerBpm] = config.lowerBpm
        this[Keys.TargetCycles] = config.targetCycles
        this[Keys.FatigueRatio] = config.fatigueRatio
        this[Keys.RecoveryFatigueRatio] = config.recoveryFatigueRatio
        this[Keys.MinBaselineSecs] = config.minBaseline.inWholeSeconds
        this[Keys.HighTimeoutSecs] = config.highPhaseTimeout.inWholeSeconds
        this[Keys.RecoveryTimeoutSecs] = config.recoveryTimeout.inWholeSeconds
        this[Keys.AgeYears] = config.ageYears
        this[Keys.RestingBpm] = config.restingBpm
        this[Keys.UpdatedAtMs] = updatedAtMs
        this[Keys.UpdatedBy] = updatedBy
        this[Keys.SeedTargetCadenceHigh] = config.seedTargetCadenceHigh
        this[Keys.SeedTargetCadenceRecovery] = config.seedTargetCadenceRecovery
        // 身長は watch では使わないが、phone との往復同期で消えないよう保持する
        val h = config.heightCm
        if (h != null) this[Keys.HeightCm] = h else remove(Keys.HeightCm)
    }

    private fun Preferences.toDocument(): SettingsDocument = SettingsDocument.from(
        config = toConfig(),
        updatedAtMs = this[Keys.UpdatedAtMs] ?: 0L,
        updatedBy = this[Keys.UpdatedBy] ?: DEVICE,
    )

    private fun Preferences.toConfig(): SessionConfig {
        val defaults = SessionConfig()
        return runCatching {
            SessionConfig(
                ageYears = this[Keys.AgeYears] ?: defaults.ageYears,
                restingBpm = this[Keys.RestingBpm] ?: defaults.restingBpm,
                upperBpm = this[Keys.UpperBpm] ?: defaults.upperBpm,
                lowerBpm = this[Keys.LowerBpm] ?: defaults.lowerBpm,
                targetCycles = this[Keys.TargetCycles] ?: defaults.targetCycles,
                fatigueRatio = this[Keys.FatigueRatio] ?: defaults.fatigueRatio,
                recoveryFatigueRatio = this[Keys.RecoveryFatigueRatio]
                    ?: defaults.recoveryFatigueRatio,
                minBaseline = this[Keys.MinBaselineSecs]?.seconds ?: defaults.minBaseline,
                highPhaseTimeout = this[Keys.HighTimeoutSecs]?.seconds ?: defaults.highPhaseTimeout,
                recoveryTimeout = this[Keys.RecoveryTimeoutSecs]?.seconds
                    ?: defaults.recoveryTimeout,
                seedTargetCadenceHigh = this[Keys.SeedTargetCadenceHigh]
                    ?: defaults.seedTargetCadenceHigh,
                seedTargetCadenceRecovery = this[Keys.SeedTargetCadenceRecovery]
                    ?: defaults.seedTargetCadenceRecovery,
                heightCm = this[Keys.HeightCm] ?: defaults.heightCm,
            )
        }.getOrElse {
            Log.w(TAG, "保存設定が不正なためデフォルトを使用", it)
            defaults
        }
    }
}

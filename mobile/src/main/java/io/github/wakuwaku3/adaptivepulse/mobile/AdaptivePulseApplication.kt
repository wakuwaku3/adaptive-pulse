package io.github.wakuwaku3.adaptivepulse.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.wakuwaku3.adaptivepulse.mobile.health.DashboardSyncManager
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import kotlinx.coroutines.launch

private const val TAG = "AdaptivePulse"

/**
 * アプリ前景化を検知して、ダッシュボード同期を即時実行する。
 * Periodic WorkManager は 1h 毎にしか走らないので、ユーザがアプリを開いた瞬間に
 * 最新の HC データを Room へ落とすために必要。
 */
class AdaptivePulseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // HC 権限が無い段階では HealthSyncWorker 自身が skip する。
                // foreground hook は KEEP で重複実行を抑止
                val granted = runCatching {
                    HealthConnectAvailability(this@AdaptivePulseApplication).isReady
                }.getOrDefault(false)
                if (!granted) {
                    Log.d(TAG, "HC 未連携なので前景化即時同期は skip")
                    return
                }
                DashboardSyncManager.enqueueForeground(this@AdaptivePulseApplication)
                owner.lifecycleScope.launch {
                    DashboardSyncManager.enqueueInitialSyncIfNeeded(this@AdaptivePulseApplication)
                }
            }
        })
    }
}

/** HC が利用可能 + 必須権限が揃っているかを軽く判定する (Application 起動の早いタイミングからでも安全に呼べる) */
private class HealthConnectAvailability(private val context: android.content.Context) {
    val isReady: Boolean
        get() {
            val source = HealthDataSource(context)
            if (!source.available) return false
            // suspend fun を blocking で呼びたくないので、ここでは「権限ダイアログ確認は worker 側に委ねる」
            // ことにして、最低限 HC SDK が available なら true 扱いにする
            return true
        }
}

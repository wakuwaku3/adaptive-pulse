package io.github.wakuwaku3.adaptivepulse.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.mobile.BuildConfig
import io.github.wakuwaku3.adaptivepulse.mobile.auth.AuthManager
import io.github.wakuwaku3.adaptivepulse.mobile.health.DashboardSyncManager
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataExporter
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DemoSeed
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PhoneSync
import io.github.wakuwaku3.adaptivepulse.mobile.ui.AdaptivePulseMobileTheme
import io.github.wakuwaku3.adaptivepulse.mobile.ui.HistoryItem
import io.github.wakuwaku3.adaptivepulse.mobile.ui.HistoryScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors
import io.github.wakuwaku3.adaptivepulse.mobile.ui.SettingsScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.appVersionName
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.computed
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Screen { History, Settings }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdaptivePulseMobileTheme {
                Root()
            }
        }
    }

    @Composable
    private fun Root() {
        val auth = remember { AuthManager(applicationContext) }
        var signedIn by remember { mutableStateOf(auth.currentUser != null) }
        if (signedIn) {
            MainScreen(onSignOut = {
                auth.signOut()
                signedIn = false
            })
        } else {
            SignInScreen(auth, onSignedIn = { signedIn = true })
        }
    }

    @Composable
    private fun SignInScreen(auth: AuthManager, onSignedIn: () -> Unit) {
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        // Scaffold で包まないと LocalContentColor が Color.Black に倒れて
        // dark theme でも本文が黒のまま不可視になる (.claude/rules/ui.md)
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "AdaptivePulse",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Sign in to sync your sessions and settings across devices.",
                    color = MobileColors.TextDim,
                )
                if (!auth.isConfigured) {
                    Text(
                        "Firebase is not configured. Place google-services.json in mobile/ and rebuild (docs/stock/setup-firebase.md).",
                        color = MobileColors.Done,
                    )
                }
                Button(
                    enabled = auth.isConfigured,
                    onClick = {
                        scope.launch {
                            auth.signInWithGoogle(this@MainActivity)
                                .onSuccess { onSignedIn() }
                                .onFailure { error = it.message }
                        }
                    },
                ) { Text("Sign in with Google") }
                // デザイン確認用: HC・Firebase なしの環境 (エミュレータ等) で UI を見たい時に使う。
                // sideload (release) では表示しない
                if (BuildConfig.DEBUG) {
                    Button(onClick = {
                        scope.launch {
                            DemoSeed.seed(applicationContext)
                            onSignedIn()
                        }
                    }) { Text("Show demo dashboard") }
                }
                error?.let { Text(it, color = MobileColors.High) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen(onSignOut: () -> Unit) {
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf(Screen.History) }
        var menuOpen by remember { mutableStateOf(false) }
        var history by remember { mutableStateOf<List<HistoryItem>?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        val settingsRepo = remember { PhoneSettingsRepository(applicationContext) }
        val settingsDoc by settingsRepo.document.collectAsState(initial = null)

        // Health Connect の権限管理 + JSON export
        val healthSource = remember { HealthDataSource(applicationContext) }
        var hcConnected by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            hcConnected = healthSource.available &&
                healthSource.grantedPermissions().containsAll(HealthDataSource.PERMISSIONS)
            if (hcConnected) {
                DashboardSyncManager.enqueuePeriodic(applicationContext)
                DashboardSyncManager.enqueueInitialSyncIfNeeded(applicationContext)
            }
        }
        val hcLauncher = rememberLauncherForActivityResult(
            contract = HealthDataSource.permissionRequestContract(),
        ) { granted ->
            hcConnected = granted.containsAll(HealthDataSource.PERMISSIONS)
            if (!hcConnected && granted.isNotEmpty()) {
                status = "Health Connect: some permissions denied"
            }
            if (hcConnected) {
                DashboardSyncManager.enqueuePeriodic(applicationContext)
                scope.launch {
                    DashboardSyncManager.enqueueInitialSyncIfNeeded(applicationContext)
                    DashboardSyncManager.enqueueForeground(applicationContext)
                }
                status = "Health Connect connected · back-filling last 5 years in background"
            }
        }

        // ダッシュボード用のローカル Room を観測する。HC 同期は WorkManager が回す
        val dashboard = remember { DashboardRepository(applicationContext) }
        val today = LocalDate.now()
        var period by remember { mutableStateOf(io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.Period.WEEK) }
        val todayEntity by dashboard.observeSnapshot(today).collectAsState(initial = null)
        val recentEntities by dashboard.observeRecent(period.days, today).collectAsState(initial = emptyList())
        val hrSamples by dashboard.observeHeartRateForDate(today)
            .collectAsState(initial = emptyList())
        val todayComputed = todayEntity?.computed()
        val recentComputed = recentEntities.sortedBy { it.date }.map { it.computed() }

        suspend fun refresh() {
            val pendingLeft = PhoneSync.syncPendingSessions(applicationContext)
            PhoneSync.reconcileSettings(applicationContext)
            val pending = PendingSessionStore(applicationContext).list()
                .map { HistoryItem(it, pending = true) }
            val remote = FirestoreSync.listSessions()
                ?.map { HistoryItem(it, pending = false) }
            history = (pending + remote.orEmpty())
                .distinctBy { it.record.id }
                .sortedByDescending { it.record.startedAtMs }
            status = when {
                remote == null && pending.isEmpty() -> "Sync not available."
                remote == null -> "Sync not available · ${pending.size} local sessions"
                pendingLeft > 0 -> "$pendingLeft sessions waiting to sync"
                else -> null
            }
            // 取得操作と一緒に HC → Room の再同期も走らせる (pull-to-refresh 相当)
            if (hcConnected) DashboardSyncManager.enqueueForeground(applicationContext)
        }

        LaunchedEffect(Unit) { refresh() }

        BackHandler(enabled = screen != Screen.History) {
            screen = Screen.History
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(titleFor(screen))
                            // sideload 後にどの release が入っているか TopAppBar で常時確認できる
                            Text(
                                text = "v${appVersionName()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        if (screen != Screen.History) {
                            IconButton(onClick = { screen = Screen.History }) {
                                Text("‹", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.History) {
                            IconButton(onClick = { scope.launch { refresh() } }) {
                                Text("↻", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Text("⋮", style = MaterialTheme.typography.headlineMedium)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            if (screen == Screen.History) {
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { menuOpen = false; screen = Screen.Settings },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export 30 days") },
                                enabled = hcConnected,
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        status = "Exporting…"
                                        val export = HealthDataExporter(applicationContext).build(30)
                                        val intent = HealthDataExporter(applicationContext).shareIntent(export)
                                        status =
                                            "Exported ${export.dailyMetrics.size} days + ${export.sessions.size} sessions"
                                        startActivity(intent)
                                    }
                                },
                            )
                            if (BuildConfig.DEBUG) {
                                DropdownMenuItem(
                                    text = { Text("Re-seed demo data") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { DemoSeed.seed(applicationContext) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                onClick = {
                                    menuOpen = false
                                    onSignOut()
                                },
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (screen) {
                    Screen.History -> {
                        val cfg = settingsDoc?.toSessionConfig() ?: SessionConfig()
                        HistoryScreen(
                            items = history,
                            statusLine = status,
                            today = todayComputed,
                            recentDays = recentComputed,
                            hrSamples = hrSamples,
                            upperBpm = cfg.upperBpm,
                            lowerBpm = cfg.lowerBpm,
                            period = period,
                            onPeriodChange = { period = it },
                        )
                    }
                    Screen.Settings -> SettingsScreen(
                        config = settingsDoc?.toSessionConfig() ?: SessionConfig(),
                        onChange = { item, newValue ->
                            scope.launch {
                                PhoneSync.updateSettingsEverywhere(applicationContext) { config ->
                                    item.write(config, newValue)
                                }
                            }
                        },
                        healthConnectConnected = hcConnected,
                        healthConnectAvailable = healthSource.available,
                        onHealthConnectToggle = { wantConnect ->
                            if (wantConnect) {
                                hcLauncher.launch(HealthDataSource.PERMISSIONS)
                            } else {
                                DashboardSyncManager.cancelAll(applicationContext)
                                status = "Revoke in Settings → Health Connect"
                                hcConnected = false
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.History -> "AdaptivePulse"
    Screen.Settings -> "Settings"
}

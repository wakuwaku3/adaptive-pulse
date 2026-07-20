package io.github.wakuwaku3.adaptivepulse.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.wakuwaku3.adaptivepulse.core.SessionConfig
import io.github.wakuwaku3.adaptivepulse.core.menu.LibraryDocument
import io.github.wakuwaku3.adaptivepulse.core.menu.LibrarySelection
import io.github.wakuwaku3.adaptivepulse.core.menu.Presets
import io.github.wakuwaku3.adaptivepulse.core.menu.SelectionKind
import io.github.wakuwaku3.adaptivepulse.mobile.BuildConfig
import io.github.wakuwaku3.adaptivepulse.mobile.auth.AuthManager
import io.github.wakuwaku3.adaptivepulse.mobile.library.PhoneLibraryRepository
import io.github.wakuwaku3.adaptivepulse.mobile.health.DashboardSyncManager
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataExporter
import io.github.wakuwaku3.adaptivepulse.mobile.health.HealthDataSource
import io.github.wakuwaku3.adaptivepulse.mobile.session.DemoSessionController
import io.github.wakuwaku3.adaptivepulse.mobile.session.LiveSessionCommander
import io.github.wakuwaku3.adaptivepulse.mobile.session.LiveSessionLauncher
import io.github.wakuwaku3.adaptivepulse.mobile.session.LiveSessionStore
import io.github.wakuwaku3.adaptivepulse.mobile.settings.PhoneSettingsRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DashboardRepository
import io.github.wakuwaku3.adaptivepulse.mobile.store.DemoSeed
import io.github.wakuwaku3.adaptivepulse.mobile.sync.FirestoreSync
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PendingSessionStore
import io.github.wakuwaku3.adaptivepulse.mobile.sync.PhoneSync
import io.github.wakuwaku3.adaptivepulse.mobile.ui.ActiveSessionScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.AdaptivePulseMobileTheme
import io.github.wakuwaku3.adaptivepulse.mobile.ui.HistoryItem
import io.github.wakuwaku3.adaptivepulse.mobile.ui.HistoryScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.LibraryScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MenuEditScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.MobileColors
import io.github.wakuwaku3.adaptivepulse.mobile.ui.ProgramEditScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.SettingsScreen
import io.github.wakuwaku3.adaptivepulse.mobile.ui.appVersionName
import io.github.wakuwaku3.adaptivepulse.mobile.ui.dashboard.computed
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

private sealed interface Screen {
    data object History : Screen

    data object Settings : Screen

    data object Library : Screen

    /** menuId = null は新規作成 */
    data class MenuEdit(val menuId: String?) : Screen

    /** programId = null は新規作成 */
    data class ProgramEdit(val programId: String?) : Screen
}

/** 戻る操作 (‹ / システム back) の遷移先 */
private fun parentOf(screen: Screen): Screen = when (screen) {
    is Screen.MenuEdit, is Screen.ProgramEdit -> Screen.Library
    else -> Screen.History
}

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
        // ライブ画面はサインイン状態に関係なく出す (受け手は本人 phone なので、
        // セッション中の表示の方が認証より優先される)。
        val live by LiveSessionStore.snapshot.collectAsState()
        live?.let {
            ActiveSession(it)
            return
        }
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
    private fun ActiveSession(snapshot: io.github.wakuwaku3.adaptivepulse.core.sync.SessionLiveSnapshot) {
        // ライブ画面表示中だけ画面オフを抑止し、full-screen intent で起動された
        // 場合に限ってロック画面の上に出す。Activity 単位で常時有効にすると
        // ダッシュボードがロック解除前に丸見えになるため、ライフサイクルに紐付ける
        val view = LocalView.current
        DisposableEffect(Unit) {
            val window = (view.context as? android.app.Activity)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(false)
                    setTurnScreenOn(false)
                }
                LiveSessionLauncher.dismiss(applicationContext)
            }
        }
        // Android 13+ の通知許可を 1 度だけ要求する (拒否されても fallback の挙動は変わらない)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* 結果は無視: 通知が出るかどうかだけが変わり、機能は壊れない */ }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        // デモモード中は watch にコマンドを投げず、ローカル DemoSessionController を直接いじる
        // (本物の watch が居ない見た目確認用なので、操作と画面の応答だけ繋がっていれば足りる)
        val isDemo by DemoSessionController.active.collectAsState()
        ActiveSessionScreen(
            snapshot = snapshot,
            onAdjustThreshold = { delta ->
                if (isDemo) DemoSessionController.adjustThreshold(delta)
                else LiveSessionCommander.adjustThreshold(applicationContext, delta)
            },
            onStop = {
                if (isDemo) DemoSessionController.stop()
                else LiveSessionCommander.stop(applicationContext)
            },
            onDone = {
                if (isDemo) DemoSessionController.done()
                else LiveSessionCommander.done(applicationContext)
            },
        )
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
                    // セッション中の phone 画面をエミュレータだけで確認するモード。
                    // 本物の watch が要らないように DemoSessionController が snapshot を回す
                    Button(onClick = { DemoSessionController.start() }) {
                        Text("Show demo session")
                    }
                }
                error?.let { Text(it, color = MobileColors.High) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen(onSignOut: () -> Unit) {
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf<Screen>(Screen.History) }
        var menuOpen by remember { mutableStateOf(false) }
        var history by remember { mutableStateOf<List<HistoryItem>?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        val settingsRepo = remember { PhoneSettingsRepository(applicationContext) }
        val settingsDoc by settingsRepo.document.collectAsState(initial = null)
        val libraryRepo = remember { PhoneLibraryRepository(applicationContext) }
        val storedLibrary by libraryRepo.stored.collectAsState(initial = null)

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
        val currentConfig = settingsDoc?.toSessionConfig() ?: SessionConfig()
        val fallbackHeightCm = currentConfig.heightCm?.toDouble()
        val todayComputed = todayEntity?.computed(
            ageYears = currentConfig.ageYears,
            fallbackHeightCm = fallbackHeightCm,
        )
        val recentComputed = recentEntities.sortedBy { it.date }.map {
            it.computed(
                ageYears = currentConfig.ageYears,
                fallbackHeightCm = fallbackHeightCm,
            )
        }

        suspend fun refresh() {
            val pendingLeft = PhoneSync.syncPendingSessions(applicationContext)
            PhoneSync.reconcileSettings(applicationContext)
            PhoneSync.reconcileLibrary(applicationContext)
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
            screen = parentOf(screen)
        }

        // 未保存 (移行前) は既存設定から hiit 移行した初期ライブラリを導出する
        val library = storedLibrary ?: LibraryDocument.initialFrom(currentConfig)
        val presetMenus = Presets.menus(currentConfig.ageYears)
        val presetPrograms = Presets.programs()

        fun saveLibrary(transform: (LibraryDocument) -> LibraryDocument) {
            scope.launch {
                PhoneSync.updateLibraryEverywhere(applicationContext, currentConfig, transform)
            }
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
                            IconButton(onClick = { screen = parentOf(screen) }) {
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
                                    text = { Text("Menus & Programs") },
                                    onClick = { menuOpen = false; screen = Screen.Library },
                                )
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
                when (val current = screen) {
                    Screen.Library -> LibraryScreen(
                        library = library,
                        presetMenus = presetMenus,
                        presetPrograms = presetPrograms,
                        onEditMenu = { menu -> screen = Screen.MenuEdit(menu.id) },
                        onDuplicateMenu = { menu ->
                            val copy = menu.copy(id = "menu-${UUID.randomUUID().toString().take(8)}", name = "${menu.name} copy")
                            saveLibrary { it.copy(menus = it.menus + copy) }
                            screen = Screen.MenuEdit(copy.id)
                        },
                        onDeleteMenu = { menu ->
                            saveLibrary { lib ->
                                lib.copy(
                                    menus = lib.menus.filterNot { it.id == menu.id },
                                    // 削除したものが選択中なら hiit に戻す (開始画面の参照切れを防ぐ)
                                    selection = if (lib.selection.kind == SelectionKind.MENU && lib.selection.id == menu.id) {
                                        LibrarySelection(SelectionKind.MENU, LibraryDocument.HIIT_MENU_ID)
                                    } else {
                                        lib.selection
                                    },
                                )
                            }
                        },
                        onCreateMenu = { screen = Screen.MenuEdit(null) },
                        onEditProgram = { program -> screen = Screen.ProgramEdit(program.id) },
                        onDeleteProgram = { program ->
                            saveLibrary { lib ->
                                lib.copy(
                                    programs = lib.programs.filterNot { it.id == program.id },
                                    selection = if (lib.selection.kind == SelectionKind.PROGRAM && lib.selection.id == program.id) {
                                        LibrarySelection(SelectionKind.MENU, LibraryDocument.HIIT_MENU_ID)
                                    } else {
                                        lib.selection
                                    },
                                )
                            }
                        },
                        onCreateProgram = { screen = Screen.ProgramEdit(null) },
                    )
                    is Screen.MenuEdit -> MenuEditScreen(
                        initial = current.menuId?.let { id ->
                            library.menu(id) ?: presetMenus.firstOrNull { it.id == id }
                        },
                        newId = remember(current) { "menu-${UUID.randomUUID().toString().take(8)}" },
                        onSave = { menu ->
                            saveLibrary { lib ->
                                val exists = lib.menus.any { it.id == menu.id }
                                lib.copy(
                                    menus = if (exists) {
                                        lib.menus.map { if (it.id == menu.id) menu else it }
                                    } else {
                                        lib.menus + menu
                                    },
                                )
                            }
                            screen = Screen.Library
                        },
                    )
                    is Screen.ProgramEdit -> ProgramEditScreen(
                        initial = current.programId?.let { library.program(it) },
                        newId = remember(current) { "program-${UUID.randomUUID().toString().take(8)}" },
                        library = library,
                        presetMenus = presetMenus,
                        onSave = { program ->
                            saveLibrary { lib ->
                                val exists = lib.programs.any { it.id == program.id }
                                lib.copy(
                                    programs = if (exists) {
                                        lib.programs.map { if (it.id == program.id) program else it }
                                    } else {
                                        lib.programs + program
                                    },
                                )
                            }
                            screen = Screen.Library
                        },
                    )
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
                        onHeightChange = { cm ->
                            scope.launch {
                                PhoneSync.updateSettingsEverywhere(applicationContext) { it.copy(heightCm = cm) }
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
                        onHealthConnectResync = {
                            DashboardSyncManager.enqueueInitialSync(applicationContext)
                            status = "Resyncing last 5 years from Health Connect…"
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
    Screen.Library -> "Menus & Programs"
    is Screen.MenuEdit -> if (screen.menuId == null) "New Menu" else "Edit Menu"
    is Screen.ProgramEdit -> if (screen.programId == null) "New Program" else "Edit Program"
}

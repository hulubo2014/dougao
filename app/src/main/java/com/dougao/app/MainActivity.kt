package com.dougao.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dougao.app.agent.AgentPhase
import com.dougao.app.agent.AgentState
import com.dougao.app.agent.FileAgent
import com.dougao.app.agent.MobileAgent
import com.dougao.app.controller.AppScanner
import com.dougao.app.controller.DeviceController
import com.dougao.app.controller.DouGaoAccessibilityService
import com.dougao.app.data.*
import com.dougao.app.files.FileGateway
import com.dougao.app.ui.OverlayService
import com.dougao.app.ui.screens.*
import com.dougao.app.ui.theme.*
import com.dougao.app.vlm.GUIOwlClient
import com.dougao.app.vlm.MAIUIClient
import com.dougao.app.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import android.util.Log

private const val TAG = "MainActivity"

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Home : Screen("home", "首页", Icons.Outlined.Home, Icons.Filled.Home)
    object Files : Screen("files", "文件", Icons.Outlined.List, Icons.Filled.List)
    object Capabilities : Screen("capabilities", "能力", Icons.Outlined.Star, Icons.Filled.Star)
    object History : Screen("history", "记录", Icons.Outlined.Notifications, Icons.Filled.Notifications)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var sessionStore: TaskSessionStore

    /** 当前所在的任务房间（豆糕 1.3.0）；null = 新任务，还没发过消息 */
    private val activeSessionId = mutableStateOf<String?>(null)

    private val mobileAgent = mutableStateOf<MobileAgent?>(null)
    private val fileAgent = mutableStateOf<FileAgent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    // 当前执行的协程 Job（用于停止任务）
    private var currentExecutionJob: kotlinx.coroutines.Job? = null
    private var fileAgentJob: kotlinx.coroutines.Job? = null

    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    // 是否正在执行（点击发送后立即为 true）
    private val isExecuting = mutableStateOf(false)

    // 当前执行的记录 ID（用于停止后跳转）
    private val currentRecordId = mutableStateOf<String?>(null)

    // 是否需要跳转到记录详情（悬浮窗停止后触发）
    private val shouldNavigateToRecord = mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            deviceController.bindService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuAvailable.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)
        // 截图优先落到 App 自己的外部私有目录：shell 能写、App 能读，最稳
        deviceController.setExternalDir(getExternalFilesDir(null))
        settingsManager = SettingsManager(this)
        executionRepository = ExecutionRepository(this)
        workspaceManager = WorkspaceManager.get(this)
        sessionStore = TaskSessionStore(this)

        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
        }

        lifecycleScope.launch {
            sessionStore.load()
            // 豆糕 1.3.2：上次被强杀时留下的「运行中」残影，启动时统一纠正成「已停止」，
            // 否则首页会一直转圈，看起来像关了还在跑。
            sessionStore.resetRunningSessions()
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        checkAndUpdateShizukuStatus()

        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.backgroundCard.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(onComplete = { settingsManager.setOnboardingSeen() })
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }
        var showShizukuHelpDialog by remember { mutableStateOf(false) }
        var hasShownShizukuHelp by remember { mutableStateOf(false) }

        val settings by settingsManager.settings.collectAsState()
        val workspaceEntries by workspaceManager.entries.collectAsState()
        var activeWorkspace by remember { mutableStateOf<WorkspaceEntry?>(null) }
        var showModelList by remember { mutableStateOf(false) }
        var showAddModel by remember { mutableStateOf(false) }
        var editingProfile by remember { mutableStateOf<ModelProfile?>(null) }
        var showModelPicker by remember { mutableStateOf(false) }
        var showThinkingPicker by remember { mutableStateOf(false) }
        var showPermissionGuide by remember { mutableStateOf(false) }

        // ---------------- 任务会话（豆糕 1.3.0）----------------
        val sessions by sessionStore.sessions.collectAsState()
        val activeSession = sessions.find { it.id == activeSessionId.value }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        // 无障碍服务是否已连接（每 1.5 秒刷新一次，用户从系统设置回来能立刻反映）
        var accessibilityConnected by remember {
            mutableStateOf(DouGaoAccessibilityService.isConnected())
        }
        LaunchedEffect(Unit) {
            while (true) {
                accessibilityConnected = DouGaoAccessibilityService.isConnected()
                kotlinx.coroutines.delay(1500)
            }
        }

        // 豆糕自己有没有 root 权限（在 Magisk / KernelSU 里授权，跟 Shizuku 无关）
        var rootAvailable by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            rootAvailable = withContext(Dispatchers.IO) { deviceController.isRootAvailable() }
        }

        // 把「高级选项」里选的通道下发给设备控制器
        LaunchedEffect(settings.accessibilityModeEnabled, settings.shizukuModeEnabled, settings.rootModeEnabled) {
            deviceController.controlPrefs = DeviceController.ControlPrefs(
                accessibility = settings.accessibilityModeEnabled,
                shizuku = settings.shizukuModeEnabled
            )
            deviceController.rootMode = settings.rootModeEnabled
        }

        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()
        // 设备是否可控：无障碍 / Shizuku / Root 任一就绪即可
        val deviceReady = isShizukuAvailable || accessibilityConnected ||
                (settings.rootModeEnabled && rootAvailable)
        val executing by remember { isExecuting }
        val navigateToRecord by remember { shouldNavigateToRecord }
        val recordId by remember { currentRecordId }

        // 文件助手状态
        val fAgent = fileAgent.value
        val fileAgentState by fAgent?.state?.collectAsState()
            ?: remember { mutableStateOf(null) }
        val fileLogs by fAgent?.logs?.collectAsState()
            ?: remember { mutableStateOf(emptyList<String>()) }
        val filePendingConfirm by fAgent?.pendingConfirm?.collectAsState()
            ?: remember { mutableStateOf(null) }

        LaunchedEffect(workspaceEntries) {
            if (activeWorkspace == null || workspaceEntries.none { it.uri == activeWorkspace?.uri }) {
                activeWorkspace = workspaceEntries.firstOrNull()
            }
        }

        LaunchedEffect(navigateToRecord, recordId) {
            if (navigateToRecord && recordId != null) {
                val record = records.find { it.id == recordId }
                if (record != null) {
                    selectedRecord = record
                    currentScreen = Screen.History
                }
                shouldNavigateToRecord.value = false
            }
        }

        LaunchedEffect(Unit) {
            if (!deviceReady && settings.hasSeenOnboarding && !hasShownShizukuHelp) {
                hasShownShizukuHelp = true
                showPermissionGuide = true
            }
        }

        // ==============================================================
        // 任务抽屉（首页右上角「三条杠」点开就是这个）
        // ==============================================================
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = colors.background,
                    drawerContentColor = colors.textPrimary,
                    modifier = Modifier.width(300.dp)
                ) {
                    SessionsDrawerContent(
                        sessions = sessions,
                        activeSessionId = activeSessionId.value,
                        onSelect = { target ->
                            // 同一时间只允许一个任务在跑：切房间前先把当前任务停下
                            if (isExecuting.value) {
                                mobileAgent.value?.stop()
                                currentExecutionJob?.cancel()
                            }
                            activeSessionId.value = target.id
                            currentScreen = Screen.Home
                            scope.launch { drawerState.close() }
                        },
                        onNewTask = {
                            if (isExecuting.value) {
                                mobileAgent.value?.stop()
                                currentExecutionJob?.cancel()
                            }
                            activeSessionId.value = null
                            mobileAgent.value?.clearLogs()
                            currentScreen = Screen.Home
                            scope.launch { drawerState.close() }
                        },
                        onDelete = { id ->
                            lifecycleScope.launch {
                                sessionStore.delete(id)
                                if (activeSessionId.value == id) activeSessionId.value = null
                            }
                        },
                        onTogglePin = { target ->
                            lifecycleScope.launch {
                                sessionStore.setPinned(target.id, !target.pinned)
                            }
                        },
                        onClearAll = {
                            lifecycleScope.launch {
                                sessionStore.clearAll()
                                activeSessionId.value = null
                            }
                        }
                    )
                }
            }
        ) {
        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background,
            bottomBar = {
                if (selectedRecord == null) {
                    NavigationBar(
                        containerColor = colors.background,
                        contentColor = colors.textPrimary,
                        tonalElevation = 0.dp
                    ) {
                        listOf(
                            Screen.Home, Screen.Files, Screen.Capabilities, Screen.History, Screen.Settings
                        ).forEach { screen ->
                            val selected = currentScreen == screen
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title, maxLines = 1) },
                                selected = selected,
                                onClick = { currentScreen = screen },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (colors.isDark) colors.textPrimary else Color.White,
                                    selectedTextColor = colors.primary,
                                    unselectedIconColor = colors.textSecondary,
                                    unselectedTextColor = colors.textSecondary,
                                    indicatorColor = colors.primary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                BackHandler(enabled = selectedRecord != null) { selectedRecord = null }

                if (selectedRecord != null) {
                    HistoryDetailScreen(record = selectedRecord!!, onBack = { selectedRecord = null })
                } else {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                LaunchedEffect(Unit) { checkAndUpdateShizukuStatus() }

                                // 正在跑就用实时状态；跑完了就回落到这个房间存下来的那一轮
                                val liveSteps = agentState?.executionSteps ?: emptyList()
                                val sessionSteps = activeSession?.steps ?: emptyList()
                                val uiSteps = if (executing) liveSteps
                                else sessionSteps.ifEmpty { liveSteps }
                                val uiSummary = if (executing) agentState?.summary
                                else activeSession?.messages?.lastOrNull { !it.isUser }?.text
                                    ?: agentState?.summary
                                val uiPhase = if (executing) {
                                    agentState?.phase ?: AgentPhase.RUNNING
                                } else {
                                    when (activeSession?.status) {
                                        SessionStatus.DONE -> AgentPhase.SUCCESS
                                        SessionStatus.FAILED, SessionStatus.STOPPED -> AgentPhase.FAILED
                                        // 关键（豆糕 1.3.2）：没在执行却还标着 RUNNING，
                                        // 只可能是上次任务被强杀 / 没来得及收尾留下的残影。
                                        // 绝不能让界面继续转圈 —— 一律当作已停止。
                                        SessionStatus.RUNNING -> AgentPhase.FAILED
                                        else -> agentState?.phase ?: AgentPhase.IDLE
                                    }
                                }
                                val uiAgentState = AgentState(
                                    isRunning = executing,
                                    isCompleted = uiPhase == AgentPhase.SUCCESS,
                                    currentStep = agentState?.currentStep ?: uiSteps.size,
                                    instruction = agentState?.instruction
                                        ?: activeSession?.lastUserText.orEmpty(),
                                    executionSteps = uiSteps,
                                    summary = uiSummary,
                                    phase = uiPhase
                                )
                                val uiLogs = if (executing) logs else {
                                    activeSession?.logs?.ifEmpty { logs } ?: logs
                                }

                                HomeScreen(
                                    agentState = uiAgentState,
                                    logs = uiLogs,
                                    onExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.activeApiKey,
                                            baseUrl = settings.activeBaseUrl,
                                            model = settings.activeModelName,
                                            maxSteps = settings.effectiveMaxSteps,
                                            isGUIAgent = settings.activeProvider.isGUIAgent,
                                            providerId = settings.activeProviderId,
                                            thinkingLevel = settings.thinkingLevel
                                        )
                                    },
                                    // 叉号：中途停下，停在原地，用户可以继续说哪里错了。
                                    // 先立刻把「运行中」放掉，否则用户点完会觉得按钮没反应。
                                    onStop = {
                                        isExecuting.value = false
                                        mobileAgent.value?.stop()
                                        // 豆糕 1.3.2：立刻把房间标成「已停止」。
                                        // 模型那边可能正卡在网络请求里（不可中断），
                                        // 要等几十秒才会真正取消，界面不能陪着一起转圈。
                                        lifecycleScope.launch { sessionStore.resetRunningSessions() }
                                        Toast.makeText(
                                            this@MainActivity,
                                            "已停下，你可以继续说哪里做错了",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    shizukuAvailable = isShizukuAvailable,
                                    accessibilityConnected = accessibilityConnected,
                                    rootAvailable = settings.rootModeEnabled && rootAvailable,
                                    currentModel = settings.activeModelName,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { showPermissionGuide = true },
                                    isExecuting = executing,
                                    modelLabel = settings.activeProfile?.name ?: settings.activeModelName,
                                    thinking = settings.activeThinking,
                                    onModelClick = { showModelPicker = true },
                                    onThinkingClick = { showThinkingPicker = true },
                                    session = activeSession,
                                    onOpenSessions = { scope.launch { drawerState.open() } }
                                )
                            }

                            Screen.Files -> FilesScreen(
                                entries = workspaceEntries,
                                activeEntry = activeWorkspace,
                                onSelectEntry = { activeWorkspace = it },
                                onRemoveEntry = { workspaceManager.removeEntry(it) },
                                onAddEntry = { uri, isDir ->
                                    val entry = workspaceManager.addEntry(uri, isDir)
                                    if (entry != null) {
                                        activeWorkspace = entry
                                        Toast.makeText(
                                            this@MainActivity,
                                            "已添加：${entry.displayName}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "添加失败，请重新选择",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                agentState = fileAgentState,
                                logs = fileLogs,
                                pendingConfirm = filePendingConfirm,
                                onConfirm = { fileAgent.value?.resolveConfirm(it) },
                                onRun = { instruction ->
                                    runFileAgent(activeWorkspace, instruction, settings)
                                },
                                onStop = { stopFileAgent() },
                                modelLabel = settings.activeProfile?.name ?: settings.activeModelName,
                                thinking = settings.activeThinking,
                                onModelClick = { showModelPicker = true },
                                onThinkingClick = { showThinkingPicker = true },
                                isRunning = fileAgentState?.isRunning == true
                            )

                            Screen.Capabilities -> CapabilitiesScreen()

                            Screen.History -> HistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) }
                            )

                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onUpdateApiKey = { settingsManager.updateApiKey(it) },
                                onUpdateBaseUrl = { settingsManager.updateBaseUrl(it) },
                                onUpdateModel = { settingsManager.updateModel(it) },
                                onUpdateCachedModels = { settingsManager.updateCachedModels(it) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                onUpdateMaxSteps = { settingsManager.updateMaxSteps(it) },
                                onUpdateAccessibilityMode = { settingsManager.updateAccessibilityModeEnabled(it) },
                                onUpdateShizukuMode = { settingsManager.updateShizukuModeEnabled(it) },
                                onUpdateSpeedMode = { settingsManager.updateSpeedMode(it) },
                                onUpdateRootModeEnabled = { settingsManager.updateRootModeEnabled(it) },
                                onUpdateSuCommandEnabled = { settingsManager.updateSuCommandEnabled(it) },
                                onSelectProvider = { settingsManager.selectProvider(it) },
                                shizukuAvailable = isShizukuAvailable,
                                accessibilityConnected = accessibilityConnected,
                                accessibilityCaptureSupported = deviceController.isAccessibilityCaptureSupported(),
                                rootAvailable = rootAvailable,
                                onRefreshRoot = { rootAvailable = deviceController.refreshRootState() },
                                onOpenAccessibilitySettings = { openAccessibilitySettings() },
                                shizukuPrivilegeLevel = if (isShizukuAvailable) {
                                    when (deviceController.getShizukuPrivilegeLevel()) {
                                        DeviceController.ShizukuPrivilegeLevel.ROOT -> "ROOT"
                                        DeviceController.ShizukuPrivilegeLevel.ADB -> "ADB"
                                        else -> "NONE"
                                    }
                                } else "NONE",
                                onFetchModels = { onSuccess, onError ->
                                    lifecycleScope.launch {
                                        val result = VLMClient.fetchModels(settings.baseUrl, settings.apiKey)
                                        result.onSuccess { onSuccess(it) }
                                            .onFailure { onError(it.message ?: "未知错误") }
                                    }
                                },
                                onManageModels = { showModelList = true }
                            )
                        }
                    }
                }
            }
        }
        }   // ← 任务抽屉（ModalNavigationDrawer）到此结束

        if (showPermissionGuide) {
            PermissionGuideDialog(
                onDismiss = { showPermissionGuide = false },
                onOpenAccessibility = {
                    showPermissionGuide = false
                    openAccessibilitySettings()
                },
                onOpenShizuku = {
                    showPermissionGuide = false
                    showShizukuHelpDialog = true
                }
            )
        }

        if (showShizukuHelpDialog) {
            ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
        }

        // ---------------- 模型相关对话框 ----------------

        if (showModelPicker) {
            ModelPickerDialog(
                profiles = settings.modelProfiles,
                activeProfileId = settings.activeProfileId,
                onSelect = { settingsManager.setActiveProfile(it) },
                onDismiss = { showModelPicker = false },
                onManage = { showModelList = true }
            )
        }

        if (showThinkingPicker) {
            ThinkingLevelDialog(
                current = settings.activeThinking,
                onSelect = { settingsManager.updateThinkingLevel(it.level) },
                onDismiss = { showThinkingPicker = false }
            )
        }

        if (showModelList) {
            ModelProfileListDialog(
                profiles = settings.modelProfiles,
                activeProfileId = settings.activeProfileId,
                onDismiss = { showModelList = false },
                onSelect = { settingsManager.setActiveProfile(it) },
                onAdd = {
                    editingProfile = null
                    showAddModel = true
                },
                onEdit = { profile ->
                    editingProfile = profile
                    showAddModel = true
                },
                onDelete = { settingsManager.deleteModelProfile(it.id) }
            )
        }

        if (showAddModel) {
            AddEditModelDialog(
                initial = editingProfile,
                onDismiss = {
                    showAddModel = false
                    editingProfile = null
                },
                onSave = { name, providerId, model, apiKey, baseUrl ->
                    val editing = editingProfile
                    if (editing == null) {
                        settingsManager.addModelProfile(name, providerId, model, apiKey, baseUrl)
                    } else {
                        settingsManager.updateModelProfile(
                            editing.copy(
                                name = name,
                                providerId = providerId,
                                model = model,
                                apiKey = apiKey,
                                baseUrl = baseUrl
                            )
                        )
                    }
                    showAddModel = false
                    editingProfile = null
                    Toast.makeText(this@MainActivity, "模型已保存", Toast.LENGTH_SHORT).show()
                },
                onFetchModels = { providerId, apiKey, baseUrl, onSuccess, onError ->
                    lifecycleScope.launch {
                        val provider = ApiProvider.ALL.find { it.id == providerId } ?: ApiProvider.ALIYUN
                        val url = baseUrl.takeIf { it.isNotBlank() } ?: provider.baseUrl
                        val result = VLMClient.fetchModels(url, apiKey)
                        result.onSuccess { onSuccess(it) }
                            .onFailure { onError(it.message ?: "未知错误") }
                    }
                }
            )
        }
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 豆糕 1.3.2：豆糕关掉时把东西收干净。
        // 以前只停了 Activity，而悬浮窗是**独立的前台服务**，会继续挂在屏幕上 ——
        // 用户看到的就是「App 都退了，那个彩色条还在转」。
        try {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
            isExecuting.value = false
            mobileAgent.value?.stop()
            OverlayService.hide(this)
        } catch (e: Exception) {
            println("[MainActivity] onDestroy cleanup failed: ${e.message}")
        }

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun checkAndUpdateShizukuStatus() {
        try {
            val binderAlive = Shizuku.pingBinder()
            if (binderAlive) {
                shizukuAvailable.value = true
                if (checkShizukuPermission()) {
                    deviceController.bindService()
                } else {
                    requestShizukuPermission()
                }
            } else {
                shizukuAvailable.value = false
            }
        } catch (e: Exception) {
            shizukuAvailable.value = false
        }
    }

    private fun refreshShizukuStatus() {
        Toast.makeText(this, "正在检查 Shizuku 状态...", Toast.LENGTH_SHORT).show()
        checkAndUpdateShizukuStatus()
        when {
            shizukuAvailable.value && checkShizukuPermission() ->
                Toast.makeText(this, "Shizuku 已连接", Toast.LENGTH_SHORT).show()
            shizukuAvailable.value ->
                Toast.makeText(this, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 无障碍
    // ------------------------------------------------------------------

    /** 跳转到系统无障碍设置页 */
    private fun openAccessibilitySettings() {
        Toast.makeText(this, "请在列表里找到「豆糕」，把开关打开", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent("android.settings.ACCESSIBILITY_SETTINGS"))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "无法打开无障碍设置，请手动前往：设置 → 无障碍 → 已下载的服务",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // 手机自动化（豆糕原有能力）
    // ------------------------------------------------------------------

    private fun runAgent(
        instruction: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        maxSteps: Int,
        isGUIAgent: Boolean = false,
        providerId: String = "",
        thinkingLevel: Int = 1
    ) {
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }
        val requiresApiKey = providerId != "mai_ui"
        if (requiresApiKey && apiKey.isBlank()) {
            Toast.makeText(this, "请先在设置里添加模型和 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 控制通道必须至少有一条可用
        val latest = settingsManager.settings.value
        deviceController.controlPrefs = DeviceController.ControlPrefs(
            accessibility = latest.accessibilityModeEnabled,
            shizuku = latest.shizukuModeEnabled
        )
        // 极速模式下发到截图通道（决定发给模型的图压到多大）
        deviceController.speedMode = latest.speedModeEnabled
        val speedMode = latest.speedModeEnabled
        if (!deviceController.canControlDevice()) {
            Toast.makeText(
                this,
                "请先开启「无障碍模式」或连接 Shizuku（设置 → 高级选项）",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // ---------------- 任务房间（豆糕 1.3.0）----------------
        // 找到当前房间；没有就开一个新的（标题取用户这句话）
        val currentSession = sessionStore.sessions.value.find { it.id == activeSessionId.value }
            ?: TaskSession(title = generateTitle(instruction))
        activeSessionId.value = currentSession.id

        // 这个房间里之前说过的话 —— 作为「纠错上下文」交给模型，
        // 它才知道上一轮错在哪、这次该怎么改
        val priorContext = currentSession.messages
            .filter { it.text.isNotBlank() }
            .takeLast(12)
            .map { it.role to it.text }

        // 先把用户这句话记进房间，界面立刻能看到
        val sessionWithUser = currentSession.copy(
            messages = currentSession.messages + SessionMessage(
                role = "user",
                text = instruction,
                kind = SessionMessage.KIND_TEXT
            ),
            status = SessionStatus.RUNNING,
            steps = emptyList(),
            logs = emptyList()
        )
        lifecycleScope.launch { sessionStore.save(sessionWithUser) }

        isExecuting.value = true

        if (isGUIAgent) {
            val guiOwlClient = GUIOwlClient(
                apiKey = apiKey,
                model = model.ifBlank { "pre-gui_owl_7b" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                guiOwlClient = guiOwlClient,
                speedMode = speedMode,
                priorContext = priorContext
            )
        } else if (providerId == "mai_ui") {
            val maiuiClient = MAIUIClient(
                baseUrl = baseUrl.ifBlank { "http://localhost:8000/v1" },
                model = model.ifBlank { "MAI-UI-2B" }
            )
            mobileAgent.value = MobileAgent(
                vlmClient = null,
                controller = deviceController,
                context = this,
                maiuiClient = maiuiClient,
                speedMode = speedMode,
                priorContext = priorContext
            )
        } else {
            val vlmClient = VLMClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model.ifBlank { "qwen3-vl-plus" },
                thinkingLevel = thinkingLevel,
                speedMode = speedMode
            )
            mobileAgent.value = MobileAgent(
                vlmClient,
                deviceController,
                this,
                speedMode = speedMode,
                priorContext = priorContext
            )
        }

        mobileAgent.value?.onStopRequested = {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
        }

        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )
        currentRecordId.value = record.id

        currentExecutionJob?.cancel()

        currentExecutionJob = lifecycleScope.launch {
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            /** 把这一轮的结果写回任务房间 */
            suspend fun writeBack(
                status: SessionStatus,
                steps: List<ExecutionStep>,
                logs: List<String>,
                replyText: String,
                replyKind: String
            ) {
                val latest = sessionStore.sessions.value
                    .find { it.id == currentSession.id } ?: sessionWithUser
                sessionStore.save(
                    latest.copy(
                        status = status,
                        steps = steps,
                        logs = logs,
                        messages = latest.messages + SessionMessage(
                            role = "assistant",
                            text = replyText,
                            kind = replyKind
                        )
                    )
                )
            }

            try {
                val result = mobileAgent.value!!.runInstruction(instruction, maxSteps)

                val st = mobileAgent.value?.state?.value
                val steps = st?.executionSteps ?: emptyList()
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    logs = currentLogs,
                    resultMessage = result.message
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                // 房间：接着写这一轮的结果
                writeBack(
                    status = if (result.success) SessionStatus.DONE else SessionStatus.FAILED,
                    steps = steps,
                    logs = currentLogs,
                    replyText = result.message,
                    replyKind = if (result.success) SessionMessage.KIND_RESULT
                    else SessionMessage.KIND_ERROR
                )

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                isExecuting.value = false
                // 注意：这里故意不 clearLogs —— 房间里要留着刚才的执行过程，
                // 用户才能看到「它到底做了啥」，然后接着纠正。
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val st = mobileAgent.value?.state?.value
                    val steps = st?.executionSteps ?: emptyList()
                    val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()

                    val updatedRecord = record.copy(
                        endTime = System.currentTimeMillis(),
                        status = ExecutionStatus.STOPPED,
                        steps = steps,
                        logs = currentLogs,
                        resultMessage = "已取消"
                    )
                    executionRepository.saveRecord(updatedRecord)
                    executionRecords.value = executionRepository.getAllRecords()

                    // 关键：停在这里不动 —— 不跳记录页、不清现场，
                    // 用户可以直接往下说「你哪里做错了」，接着改。
                    writeBack(
                        status = SessionStatus.STOPPED,
                        steps = steps,
                        logs = currentLogs,
                        replyText = "已经停下了。你直接告诉我哪里做错了 / 想怎么改，我接着来。",
                        replyKind = SessionMessage.KIND_TEXT
                    )

                    isExecuting.value = false
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()
                    val updatedRecord = record.copy(
                        endTime = System.currentTimeMillis(),
                        status = ExecutionStatus.FAILED,
                        logs = currentLogs,
                        resultMessage = "错误: ${e.message}"
                    )
                    executionRepository.saveRecord(updatedRecord)
                    executionRecords.value = executionRepository.getAllRecords()

                    writeBack(
                        status = SessionStatus.FAILED,
                        steps = mobileAgent.value?.state?.value?.executionSteps ?: emptyList(),
                        logs = currentLogs,
                        replyText = "出错了：${e.message}",
                        replyKind = SessionMessage.KIND_ERROR
                    )

                    isExecuting.value = false
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 文件助手（豆糕新增，完全不操作屏幕）
    // ------------------------------------------------------------------

    private fun runFileAgent(entry: WorkspaceEntry?, instruction: String, settings: AppSettings) {
        if (entry == null) {
            Toast.makeText(this, "请先添加一个文件夹或文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入你的需求", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = settings.activeApiKey
        val providerId = settings.activeProviderId
        if (providerId == "mai_ui" || providerId == "gui_owl") {
            Toast.makeText(this, "该服务商暂不支持文件模式，请在输入框切换成普通模型", Toast.LENGTH_LONG).show()
            return
        }
        if (apiKey.isBlank()) {
            Toast.makeText(this, "请先添加模型并填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val vlm = VLMClient(
            apiKey = apiKey,
            baseUrl = settings.activeBaseUrl.ifBlank {
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            },
            model = settings.activeModelName.ifBlank { "qwen3-vl-plus" },
            thinkingLevel = settings.thinkingLevel,
            speedMode = settings.speedModeEnabled
        )

        val agent = FileAgent(
            context = this,
            gateway = FileGateway(this),
            vlm = vlm,
            entry = entry
        )
        agent.useOriginalFile = !entry.isDirectory
        fileAgent.value = agent

        fileAgentJob?.cancel()
        fileAgentJob = lifecycleScope.launch {
            val result = agent.run(instruction, maxSteps = 25)
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFileAgent() {
        // 先停状态再取消协程，让界面立刻有反应（否则要等模型回包才变）
        fileAgent.value?.stop()
        fileAgentJob?.cancel()
        fileAgentJob = null
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
    }

    /**
     * 任务标题：直接用用户那句话（截断），比"打开应用"这种猜的标签好认得多——
     * 抽屉里一眼就能看出哪个房间是干嘛的。
     */
    private fun generateTitle(instruction: String): String {
        val clean = instruction.trim().replace("\n", " ").replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return "新任务"
        return if (clean.length > 18) clean.take(18) + "…" else clean
    }
}

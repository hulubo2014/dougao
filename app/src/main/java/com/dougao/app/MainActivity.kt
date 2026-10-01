package com.dougao.app

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
import com.dougao.app.agent.FileAgent
import com.dougao.app.agent.MobileAgent
import com.dougao.app.controller.AppScanner
import com.dougao.app.controller.DeviceController
import com.dougao.app.data.*
import com.dougao.app.files.FileGateway
import com.dougao.app.ui.screens.*
import com.dougao.app.ui.theme.*
import com.dougao.app.vlm.GUIOwlClient
import com.dougao.app.vlm.MAIUIClient
import com.dougao.app.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
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

        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()
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
            if (!isShizukuAvailable && settings.hasSeenOnboarding && !hasShownShizukuHelp) {
                hasShownShizukuHelp = true
                showShizukuHelpDialog = true
            }
        }

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
                                HomeScreen(
                                    agentState = agentState,
                                    logs = logs,
                                    onExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.activeApiKey,
                                            baseUrl = settings.activeBaseUrl,
                                            model = settings.activeModelName,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.activeProvider.isGUIAgent,
                                            providerId = settings.activeProviderId,
                                            thinkingLevel = settings.thinkingLevel
                                        )
                                    },
                                    onStop = { mobileAgent.value?.stop() },
                                    shizukuAvailable = isShizukuAvailable,
                                    currentModel = settings.activeModelName,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { showShizukuHelpDialog = true },
                                    isExecuting = executing,
                                    modelLabel = settings.activeProfile?.name ?: settings.activeModelName,
                                    thinking = settings.activeThinking,
                                    onModelClick = { showModelPicker = true },
                                    onThinkingClick = { showThinkingPicker = true }
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
                                onUpdateCloudCrashReport = { enabled ->
                                    settingsManager.updateCloudCrashReportEnabled(enabled)
                                    App.getInstance().updateCloudCrashReportEnabled(enabled)
                                },
                                onUpdateRootModeEnabled = { settingsManager.updateRootModeEnabled(it) },
                                onUpdateSuCommandEnabled = { settingsManager.updateSuCommandEnabled(it) },
                                onSelectProvider = { settingsManager.selectProvider(it) },
                                shizukuAvailable = isShizukuAvailable,
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
                guiOwlClient = guiOwlClient
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
                maiuiClient = maiuiClient
            )
        } else {
            val vlmClient = VLMClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model.ifBlank { "qwen3-vl-plus" },
                thinkingLevel = thinkingLevel
            )
            mobileAgent.value = MobileAgent(vlmClient, deviceController, this)
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

            try {
                val result = mobileAgent.value!!.runInstruction(instruction, maxSteps)

                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()
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

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                isExecuting.value = false

                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val agentState = mobileAgent.value?.state?.value
                    val steps = agentState?.executionSteps ?: emptyList()
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

                    isExecuting.value = false
                    Toast.makeText(this@MainActivity, "任务已停止", Toast.LENGTH_SHORT).show()
                    mobileAgent.value?.clearLogs()
                    shouldNavigateToRecord.value = true
                }
            } catch (e: Exception) {
                val currentLogs = mobileAgent.value?.logs?.value ?: emptyList()
                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    logs = currentLogs,
                    resultMessage = "错误: ${e.message}"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                isExecuting.value = false
                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
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
            thinkingLevel = settings.thinkingLevel
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
        fileAgent.value?.stop()
        fileAgentJob?.cancel()
        fileAgentJob = null
    }

    private fun generateTitle(instruction: String): String {
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }
}

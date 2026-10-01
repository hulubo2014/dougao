package com.dougao.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.dougao.app.BuildConfig
import com.dougao.app.data.ApiProvider
import com.dougao.app.data.AppSettings
import com.dougao.app.data.ProjectInfo
import com.dougao.app.ui.theme.BaoziTheme
import com.dougao.app.ui.theme.ThemeMode
import com.dougao.app.utils.CrashHandler

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateApiKey: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateCachedModels: (List<String>) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateMaxSteps: (Int) -> Unit,
    onUpdateAccessibilityMode: (Boolean) -> Unit,
    onUpdateShizukuMode: (Boolean) -> Unit,
    onUpdateSpeedMode: (Boolean) -> Unit = {},
    onUpdateRootModeEnabled: (Boolean) -> Unit,
    onUpdateSuCommandEnabled: (Boolean) -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    shizukuAvailable: Boolean,
    accessibilityConnected: Boolean = false,
    /** 无障碍通道能不能截图（安卓 11 以下不行，开关会置灰） */
    accessibilityCaptureSupported: Boolean = true,
    /** 豆糕自己有没有 root 权限 */
    rootAvailable: Boolean = false,
    /** 用户点了 Root 开关时，去重新探测一次 root */
    onRefreshRoot: () -> Unit = {},
    shizukuPrivilegeLevel: String = "ADB", // "ADB", "ROOT", "NONE"
    onOpenAccessibilitySettings: () -> Unit = {},
    onFetchModels: ((onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    // ---- 豆糕新增：多模型管理入口 ----
    onManageModels: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMaxStepsDialog by remember { mutableStateOf(false) }
    var showShizukuHelpDialog by remember { mutableStateOf(false) }
    var showOverlayHelpDialog by remember { mutableStateOf(false) }
    var showRootModeWarningDialog by remember { mutableStateOf(false) }
    var showSuCommandWarningDialog by remember { mutableStateOf(false) }
    var showAccessibilityHelpDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        text = "设置",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Text(
                        text = "配置 API 和应用选项",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // 连接状态卡片
        item {
            StatusCard(
                shizukuAvailable = shizukuAvailable,
                // 豆糕 1.3.2：必须「开关打开」且「系统服务真的连着」才算无障碍可用。
                // 以前只看系统服务连没连，用户把开关关了、横幅还显示「无障碍已就绪」。
                accessibilityConnected = settings.accessibilityModeEnabled && accessibilityConnected
            )
        }

        // 外观设置分组
        item {
            SettingsSection(title = "外观")
        }

        // 主题模式设置
        item {
            SettingsItem(
                icon = if (colors.isDark) Icons.Default.Star else Icons.Outlined.Star,
                title = "主题模式",
                subtitle = when (settings.themeMode) {
                    ThemeMode.LIGHT -> "浅色模式"
                    ThemeMode.DARK -> "深色模式"
                    ThemeMode.SYSTEM -> "跟随系统"
                },
                onClick = { showThemeDialog = true }
            )
        }

        // 执行设置分组
        item {
            SettingsSection(title = "执行设置")
        }

        // 最大步数设置
        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "最大执行步数",
                subtitle = settings.maxStepsLabel,
                onClick = { showMaxStepsDialog = true }
            )
        }

        // ---- 极速模式（豆糕 1.2.1 新增）----
        item {
            val enabled = settings.speedModeEnabled
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                (if (enabled) colors.success else colors.primary).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = if (enabled) colors.success else colors.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "极速模式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (enabled) {
                                "已开启 · 不做深度思考、截图更轻、等待更短，每步快好几倍"
                            } else {
                                "已关闭 · 模型会先想清楚再动手，更稳但更慢"
                            },
                            fontSize = 13.sp,
                            color = if (enabled) colors.success else colors.textSecondary,
                            maxLines = 3
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onUpdateSpeedMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.success,
                            checkedTrackColor = colors.success.copy(alpha = 0.5f),
                            uncheckedThumbColor = colors.textHint,
                            uncheckedTrackColor = colors.backgroundInput
                        )
                    )
                }
            }
        }

        // ================= 高级选项：控制通道 =================
        item {
            SettingsSection(title = "高级选项")
        }

        // ---- 无障碍模式 ----
        item {
            val supported = accessibilityCaptureSupported
            val enabled = settings.accessibilityModeEnabled && supported
            val ready = accessibilityConnected
            val accent = when {
                !supported -> colors.textHint
                enabled && ready -> colors.success
                else -> colors.primary
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        if (!supported) return@clickable
                        if (!enabled) {
                            onUpdateAccessibilityMode(true)
                            if (!ready) onOpenAccessibilitySettings()
                        } else if (!ready) {
                            onOpenAccessibilitySettings()
                        } else {
                            showAccessibilityHelpDialog = true
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "无障碍模式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (supported) colors.textPrimary else colors.textHint
                        )
                        Text(
                            text = when {
                                !supported ->
                                    "本机是安卓 11 以下，无障碍拿不到画面 · 请用 Shizuku 或 Root 模式"
                                !enabled && ready ->
                                    "未开启 · 系统里的无障碍服务还开着，可在系统设置里关掉"
                                !enabled -> "未开启 · 打开后无需 Shizuku 就能控屏"
                                ready -> "已开启 · 系统服务已连接，可正常控屏"
                                else -> "已开启 · 还需去系统设置里打开「豆糕」"
                            },
                            fontSize = 13.sp,
                            color = when {
                                !supported -> colors.textHint
                                !enabled -> colors.textSecondary
                                ready -> colors.success
                                else -> colors.error
                            },
                            maxLines = 3
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            if (!supported) return@Switch
                            onUpdateAccessibilityMode(on)
                            if (on && !ready) onOpenAccessibilitySettings()
                        },
                        enabled = supported,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.success,
                            checkedTrackColor = colors.success.copy(alpha = 0.5f),
                            uncheckedThumbColor = colors.textHint,
                            uncheckedTrackColor = colors.backgroundInput,
                            disabledUncheckedThumbColor = colors.textHint.copy(alpha = 0.5f),
                            disabledUncheckedTrackColor = colors.backgroundInput.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // ---- Shizuku 模式 ----
        item {
            val enabled = settings.shizukuModeEnabled
            val connected = shizukuAvailable
            val levelText = when (shizukuPrivilegeLevel) {
                "ROOT" -> "Root 权限 (UID 0)"
                "ADB" -> "ADB 权限 (UID 2000)"
                else -> "未连接"
            }
            val accent = if (connected) colors.primary else colors.textHint

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        if (!connected) showShizukuHelpDialog = true
                        else if (!enabled) onUpdateShizukuMode(true)
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Shizuku 模式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = when {
                                !connected -> "Shizuku 未连接 · 点击查看安装指南"
                                enabled -> "已连接 · $levelText"
                                else -> "已连接但未启用 · 点击开启"
                            },
                            fontSize = 13.sp,
                            color = if (connected && enabled) colors.primary else colors.textSecondary,
                            maxLines = 2
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            onUpdateShizukuMode(on)
                            if (on && !connected) showShizukuHelpDialog = true
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.primary,
                            checkedTrackColor = colors.primary.copy(alpha = 0.5f),
                            uncheckedThumbColor = colors.textHint,
                            uncheckedTrackColor = colors.backgroundInput
                        )
                    )
                }
            }
        }

        // ---- Root 模式 ----
        // 语义：在 root 管理器（Magisk / KernelSU）里**直接给豆糕授权**，
        // 豆糕就拿到 root 身份，可以自己直接操作手机 —— 不需要 Shizuku、也不需要无障碍。
        item {
            val enabled = settings.rootModeEnabled
            val accent = when {
                enabled && rootAvailable -> colors.error
                enabled -> colors.primary
                else -> colors.textHint
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable {
                        if (!enabled) {
                            onRefreshRoot()
                            showRootModeWarningDialog = true
                        } else {
                            onUpdateRootModeEnabled(false)
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Root 模式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (enabled) colors.textPrimary else colors.textHint
                        )
                        Text(
                            text = when {
                                !enabled -> "给豆糕 root 权限，它就能直接操作手机（无需 Shizuku）"
                                rootAvailable -> "已开启 · 已拿到 root 权限，可直接控屏"
                                else -> "已开启 · 还没拿到 root 权限，请到 Root 管理器里授权「豆糕」"
                            },
                            fontSize = 13.sp,
                            color = when {
                                !enabled -> colors.textHint
                                rootAvailable -> colors.error
                                else -> colors.primary
                            },
                            maxLines = 3
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            if (on) {
                                onRefreshRoot()
                                showRootModeWarningDialog = true
                            } else {
                                onUpdateRootModeEnabled(false)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.error,
                            checkedTrackColor = colors.error.copy(alpha = 0.5f),
                            uncheckedThumbColor = colors.textHint,
                            uncheckedTrackColor = colors.backgroundInput,
                            disabledCheckedThumbColor = colors.textHint,
                            disabledCheckedTrackColor = colors.backgroundInput,
                            disabledUncheckedThumbColor = colors.textHint.copy(alpha = 0.5f),
                            disabledUncheckedTrackColor = colors.backgroundInput.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        // su -c 开关（仅在 Root 模式开启时显示）
        if (settings.rootModeEnabled) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许 su -c 命令",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (settings.suCommandEnabled) "AI 可执行 Root 命令" else "禁止执行 su -c",
                                fontSize = 13.sp,
                                color = if (settings.suCommandEnabled) colors.error else colors.textSecondary,
                                maxLines = 1
                            )
                        }
                        Switch(
                            checked = settings.suCommandEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showSuCommandWarningDialog = true
                                } else {
                                    onUpdateSuCommandEnabled(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.error,
                                checkedTrackColor = colors.error.copy(alpha = 0.5f),
                                uncheckedThumbColor = colors.textHint,
                                uncheckedTrackColor = colors.backgroundInput
                            )
                        )
                    }
                }
            }
        }

        // 三个通道都关掉时给个提醒
        if (!settings.accessibilityModeEnabled &&
            !settings.shizukuModeEnabled &&
            !settings.rootModeEnabled
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "无障碍、Shizuku、Root 三个通道都没开启，豆糕将无法操作手机。请至少开启其中一个。",
                            fontSize = 12.sp,
                            color = colors.error
                        )
                    }
                }
            }
        }

        // 模型管理分组
        // 说明：原来的「API 服务商 / API Key / 模型」三个入口已移除，
        // 服务商、Key、模型全部由「模型管理」按模型档案统一维护，避免两处配置打架。
        item {
            SettingsSection(title = "模型配置")
        }

        // 豆糕新增：多模型管理（可添加多个模型，输入框处随时切换）
        item {
            SettingsItem(
                icon = Icons.Default.List,
                title = "模型管理",
                subtitle = if (settings.modelProfiles.isEmpty()) {
                    "还没有添加模型，点这里添加"
                } else {
                    "已添加 ${settings.modelProfiles.size} 个 · 当前：${settings.activeProfile?.name ?: settings.activeModelName}"
                },
                onClick = { onManageModels() }
            )
        }

        // 反馈分组（豆糕 1.2.0：已彻底移除「云端崩溃上报」，日志只留在本地）
        item {
            SettingsSection(title = "反馈与调试")
        }

        item {
            val context = LocalContext.current
            val logStats = remember { mutableStateOf(CrashHandler.getLogStats(context)) }

            SettingsItem(
                icon = Icons.Default.Info,
                title = "导出日志",
                subtitle = logStats.value,
                onClick = {
                    CrashHandler.shareLogs(context)
                }
            )
        }

        item {
            val context = LocalContext.current
            var showClearDialog by remember { mutableStateOf(false) }

            SettingsItem(
                icon = Icons.Default.Close,
                title = "清除日志",
                subtitle = "删除所有本地日志文件",
                onClick = { showClearDialog = true }
            )

            if (showClearDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    containerColor = BaoziTheme.colors.backgroundCard,
                    title = { Text("确认清除", color = BaoziTheme.colors.textPrimary) },
                    text = { Text("确定要删除所有日志文件吗？", color = BaoziTheme.colors.textSecondary) },
                    confirmButton = {
                        TextButton(onClick = {
                            CrashHandler.clearLogs(context)
                            showClearDialog = false
                            android.widget.Toast.makeText(context, "日志已清除", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("确定", color = BaoziTheme.colors.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) {
                            Text("取消", color = BaoziTheme.colors.textSecondary)
                        }
                    }
                )
            }
        }

        // 帮助分组
        item {
            SettingsSection(title = "帮助")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Star,
                title = "无障碍使用指南",
                subtitle = "了解如何开启无障碍服务（推荐，免 Shizuku）",
                onClick = { showAccessibilityHelpDialog = true }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "Shizuku 使用指南",
                subtitle = "了解如何安装和配置 Shizuku",
                onClick = { showShizukuHelpDialog = true }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "悬浮窗权限说明",
                subtitle = "了解为什么需要悬浮窗权限",
                onClick = { showOverlayHelpDialog = true }
            )
        }

        // 关于分组
        item {
            SettingsSection(title = "关于")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "版本",
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "豆糕 DouGao",
                subtitle = "手机自动化 + 文件直改的 AI 助手",
                onClick = {
                    // 点击跳转到项目开源仓库
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(ProjectInfo.REPO_URL))
                        )
                    }
                }
            )
        }

        // 底部间距
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        ThemeSelectDialog(
            currentTheme = settings.themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                onUpdateThemeMode(it)
                showThemeDialog = false
            }
        )
    }

    // 最大步数设置对话框
    if (showMaxStepsDialog) {
        MaxStepsDialog(
            currentSteps = settings.maxSteps,
            onDismiss = { showMaxStepsDialog = false },
            onConfirm = {
                onUpdateMaxSteps(it)
                showMaxStepsDialog = false
            }
        )
    }

    // Shizuku 帮助对话框
    if (showShizukuHelpDialog) {
        ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
    }

    // 无障碍帮助对话框
    if (showAccessibilityHelpDialog) {
        AccessibilityHelpDialog(
            onDismiss = { showAccessibilityHelpDialog = false },
            onOpenSettings = {
                showAccessibilityHelpDialog = false
                onOpenAccessibilitySettings()
            }
        )
    }

    // 悬浮窗权限帮助对话框
    if (showOverlayHelpDialog) {
        OverlayHelpDialog(onDismiss = { showOverlayHelpDialog = false })
    }

    // Root 模式警告对话框
    if (showRootModeWarningDialog) {
        RootModeWarningDialog(
            onDismiss = { showRootModeWarningDialog = false },
            onConfirm = {
                onUpdateRootModeEnabled(true)
                // 用户可能是刚在 Root 管理器里点了「允许」，再探一次
                onRefreshRoot()
                showRootModeWarningDialog = false
            }
        )
    }

    // su -c 命令警告对话框
    if (showSuCommandWarningDialog) {
        SuCommandWarningDialog(
            onDismiss = { showSuCommandWarningDialog = false },
            onConfirm = {
                onUpdateSuCommandEnabled(true)
                showSuCommandWarningDialog = false
            }
        )
    }
}

@Composable
fun StatusCard(
    shizukuAvailable: Boolean,
    accessibilityConnected: Boolean = false
) {
    val colors = BaoziTheme.colors
    val ready = shizukuAvailable || accessibilityConnected

    val title = when {
        accessibilityConnected && shizukuAvailable -> "无障碍 + Shizuku 都已就绪"
        accessibilityConnected -> "无障碍服务已连接"
        shizukuAvailable -> "Shizuku 已连接"
        else -> "设备控制未就绪"
    }
    val subtitle = when {
        accessibilityConnected && shizukuAvailable -> "两条通道都可用，控屏最稳"
        accessibilityConnected -> "无需 Shizuku，设备控制功能可用"
        shizukuAvailable -> "设备控制功能可用"
        else -> "请在下方「高级选项」里开启无障碍模式，或连接 Shizuku"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) colors.success.copy(alpha = 0.15f) else colors.error.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (ready) colors.success else colors.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ready) colors.success else colors.error
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String) {
    val colors = BaoziTheme.colors
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = colors.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}



@Composable
fun ThemeSelectDialog(
    currentTheme: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择主题", color = colors.textPrimary)
        },
        text = {
            Column {
                listOf(
                    ThemeMode.LIGHT to "浅色模式",
                    ThemeMode.DARK to "深色模式",
                    ThemeMode.SYSTEM to "跟随系统"
                ).forEach { (mode, label) ->
                    val isSelected = mode == currentTheme
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(2.dp, colors.textHint, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = if (isSelected) colors.primary else colors.textPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun ApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var key by remember { mutableStateOf(currentKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("API Key", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "请输入您的 API Key",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundInput)
                        .padding(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = key,
                            onValueChange = { key = it },
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(12.dp)
                        )
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                text = if (showKey) "隐藏" else "显示",
                                fontSize = 12.sp,
                                color = colors.textHint
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key) }) {
                Text("确定", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}


/**
 * 模型选择对话框（合并了自定义输入和从 API 获取）
 */
@Composable
fun ModelSelectDialogWithFetch(
    currentModel: String,
    cachedModels: List<String>,
    hasApiKey: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onFetchModels: ((onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    onUpdateCachedModels: (List<String>) -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var customModel by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // 默认推荐模型
    val defaultModel = "qwen3-vl-plus"

    // 过滤后的模型列表
    val filteredModels = remember(cachedModels, searchQuery) {
        if (searchQuery.isBlank()) {
            cachedModels
        } else {
            cachedModels.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择模型", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 默认推荐模型
                Text(
                    text = "推荐模型",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textHint,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val isDefaultSelected = currentModel == defaultModel
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(defaultModel) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDefaultSelected) colors.primary.copy(alpha = 0.15f) else colors.backgroundInput
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDefaultSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(2.dp, colors.textHint, CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = defaultModel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDefaultSelected) colors.primary else colors.textPrimary
                            )
                            Text(
                                text = "阿里云通义千问视觉模型",
                                fontSize = 11.sp,
                                color = colors.textHint
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 自定义模型输入
                Text(
                    text = "自定义模型",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textHint,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.backgroundInput)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (customModel.isEmpty()) {
                            Text(
                                text = "输入模型名称，如 gpt-4o",
                                color = colors.textHint,
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 确认按钮
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(enabled = customModel.isNotBlank()) {
                                onSelect(customModel.trim())
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (customModel.isNotBlank()) colors.primary else colors.backgroundInput
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "确认",
                                tint = if (customModel.isNotBlank()) Color.White else colors.textHint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 从 API 获取模型 - 更明显的按钮
                if (onFetchModels != null) {
                    Button(
                        onClick = {
                            if (!hasApiKey) {
                                android.widget.Toast.makeText(context, "请先设置 API Key", android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isLoading = true
                            onFetchModels(
                                { models ->
                                    isLoading = false
                                    onUpdateCachedModels(models)
                                    android.widget.Toast.makeText(context, "获取到 ${models.size} 个模型", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                { error ->
                                    isLoading = false
                                    android.widget.Toast.makeText(context, "获取失败: $error", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        enabled = !isLoading && hasApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            disabledContainerColor = colors.backgroundInput
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("获取中...", fontSize = 14.sp, color = Color.White)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (hasApiKey) Color.White else colors.textHint
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "从 API 获取可用模型",
                                fontSize = 14.sp,
                                color = if (hasApiKey) Color.White else colors.textHint
                            )
                        }
                    }

                    if (cachedModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "API 模型列表 (${cachedModels.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textHint,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 搜索框（模型数量超过 10 个时显示）
                    if (cachedModels.size > 10) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.backgroundInput)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = colors.textHint,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索模型...",
                                            color = colors.textHint,
                                            fontSize = 14.sp
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = colors.textPrimary,
                                            fontSize = 14.sp
                                        ),
                                        cursorBrush = SolidColor(colors.primary),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清除",
                                        tint = colors.textHint,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchQuery = "" }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 模型列表
                if (cachedModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hasApiKey) "点击「从 API 获取」加载模型列表" else "请先设置 API Key",
                            fontSize = 13.sp,
                            color = colors.textHint
                        )
                    }
                } else if (filteredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有匹配「$searchQuery」的模型",
                            fontSize = 13.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    // 显示过滤结果数量
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = "找到 ${filteredModels.size} 个模型",
                            fontSize = 11.sp,
                            color = colors.textHint,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    filteredModels.forEach { model ->
                        val isSelected = model == currentModel
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onSelect(model) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, colors.textHint, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = model,
                                    fontSize = 14.sp,
                                    color = if (isSelected) colors.primary else colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

private fun maskApiKey(key: String): String {
    return if (key.length > 8) {
        "${key.take(4)}****${key.takeLast(4)}"
    } else {
        "****"
    }
}

@Composable
fun ShizukuHelpDialog(onDismiss: () -> Unit) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("Shizuku 使用指南", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpStep(
                    number = "1",
                    title = "下载 Shizuku",
                    description = "从 Google Play 或 GitHub 下载 Shizuku 应用"
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 下载按钮
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                        )
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往下载 Shizuku", color = Color.White)
                }
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "2",
                    title = "启动 Shizuku",
                    description = "打开 Shizuku 应用，根据您的设备选择启动方式：\n\n• 无线调试（推荐）：需要 Android 11+，在开发者选项中开启无线调试\n• 连接电脑：通过 ADB 命令启动"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "3",
                    title = "授权豆糕",
                    description = "在 Shizuku 的「应用管理」中找到「豆糕」，点击授权按钮"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "4",
                    title = "开始使用",
                    description = "授权完成后，返回豆糕应用，即可开始使用"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = colors.primary)
            }
        }
    )
}

/**
 * 控屏权限引导：无障碍 或 Shizuku 二选一
 */
@Composable
fun PermissionGuideDialog(
    onDismiss: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    val colors = BaoziTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("先给豆糕控屏权限", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "豆糕要替你操作手机，需要下面任意一种权限。开启一种就够了。",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 无障碍（推荐）
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAccessibility),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primary.copy(alpha = 0.10f)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "无障碍模式（推荐）",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "系统自带，不用装任何东西；输入中文最稳，Android 11+ 还能直接截图。",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Shizuku
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenShizuku),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.backgroundInput
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Shizuku 模式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "需要另外安装 Shizuku 应用并授权，兼容更多老机型。",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后再说", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun AccessibilityHelpDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = BaoziTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("无障碍使用指南", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "无障碍模式是豆糕推荐的控屏方式 —— 它是安卓官方给自动化留的正门，不需要装 Shizuku、不需要 root、不用连电脑。",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "1",
                    title = "打开无障碍设置",
                    description = "点下面的按钮，会跳到「系统设置 → 无障碍 → 已下载的服务」"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "2",
                    title = "找到并打开「豆糕」",
                    description = "在列表里找到「豆糕」，点进去把开关打开，弹窗里点「允许」"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "3",
                    title = "回来即可使用",
                    description = "返回豆糕，设置页顶部的状态卡会变成「无障碍服务已连接」"
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "无障碍模式能做什么？",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                BulletPoint("点击、长按、滑动、双击")
                BulletPoint("输入中文、emoji（比 Shizuku 的 input text 更稳）")
                BulletPoint("返回 / 主页 / 多任务")
                BulletPoint("Android 11 及以上还能直接截图")

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "隐私说明：无障碍服务只在你主动下达任务时被调用，不监听、不记录、不上报任何屏幕内容，也不会在后台自动操作。",
                    fontSize = 12.sp,
                    color = colors.textHint,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
            ) {
                Text("去开启无障碍", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun OverlayHelpDialog(onDismiss: () -> Unit) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("悬浮窗权限说明", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "为什么需要悬浮窗权限？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "豆糕在执行任务时需要显示悬浮窗来：",
                    fontSize = 14.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("显示当前执行进度")
                BulletPoint("提供停止按钮，随时中断任务")
                BulletPoint("在其他应用上方显示状态信息")

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "如何开启？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. 点击执行任务时会自动提示\n2. 或前往：设置 > 应用 > 豆糕 > 悬浮窗权限\n3. 开启「允许显示在其他应用上层」",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "隐私安全",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "悬浮窗仅在任务执行期间显示，不会收集任何个人信息。任务完成后悬浮窗会自动消失。",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = colors.primary)
            }
        }
    )
}

@Composable
private fun HelpStep(
    number: String,
    title: String,
    description: String
) {
    val colors = BaoziTheme.colors
    Row {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = colors.textSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    ) {
        Text(
            text = "•",
            fontSize = 14.sp,
            color = colors.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = colors.textPrimary
        )
    }
}

@Composable
fun MaxStepsDialog(
    currentSteps: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = BaoziTheme.colors
    // currentSteps <= 0 视为「不限制」
    var unlimited by remember { mutableStateOf(currentSteps <= 0) }
    var steps by remember { mutableStateOf((if (currentSteps <= 0) 50 else currentSteps).toFloat()) }

    val presets = listOf(0, 25, 50, 100)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("最大执行步数", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "设置 Agent 单次任务的最大执行步数。步数越多，能完成的任务越复杂，但消耗的 token 也越多。选「不限制」就交给模型自己判断什么时候结束。",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 当前值显示
                Text(
                    text = if (unlimited) "不限制" else "${steps.toInt()} 步",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 不限制开关
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { unlimited = !unlimited },
                    shape = RoundedCornerShape(10.dp),
                    color = if (unlimited) colors.primary.copy(alpha = 0.12f) else colors.backgroundInput
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "不限制",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (unlimited) colors.primary else colors.textPrimary
                            )
                            Text(
                                text = "一直执行到任务完成（注意 token 消耗）",
                                fontSize = 11.sp,
                                color = colors.textHint
                            )
                        }
                        Switch(
                            checked = unlimited,
                            onCheckedChange = { unlimited = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.primary,
                                checkedTrackColor = colors.primary.copy(alpha = 0.5f),
                                uncheckedThumbColor = colors.textHint,
                                uncheckedTrackColor = colors.backgroundInput
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 滑块
                Slider(
                    value = steps,
                    onValueChange = { steps = it },
                    valueRange = 5f..100f,
                    steps = 18, // (100-5)/5 - 1 = 18 个刻度点，每 5 步一个
                    enabled = !unlimited,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.backgroundInput,
                        disabledThumbColor = colors.textHint,
                        disabledActiveTrackColor = colors.textHint.copy(alpha = 0.4f),
                        disabledInactiveTrackColor = colors.backgroundInput
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // 范围提示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "5",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "100",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 快捷选项
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        val selected = if (preset == 0) unlimited else (!unlimited && steps.toInt() == preset)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (preset == 0) {
                                        unlimited = true
                                    } else {
                                        unlimited = false
                                        steps = preset.toFloat()
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) colors.primary else colors.backgroundInput
                        ) {
                            Text(
                                text = if (preset == 0) "不限制" else "$preset",
                                fontSize = 13.sp,
                                color = if (selected) Color.White else colors.textSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (unlimited) 0 else steps.toInt()) }) {
                Text("确定", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

/**
 * 服务商选择对话框
 */
@Composable
fun ProviderSelectDialog(
    currentProviderId: String,
    customBaseUrl: String,
    onDismiss: () -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    onUpdateCustomUrl: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var selectedProviderId by remember { mutableStateOf(currentProviderId) }
    var customUrl by remember { mutableStateOf(customBaseUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("API 服务商", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "选择 API 服务商（支持 OpenAI 兼容接口）",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 服务商列表
                ApiProvider.ALL.forEach { provider ->
                    val isSelected = provider.id == selectedProviderId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedProviderId = provider.id
                                onSelectProvider(provider)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, colors.textHint, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = provider.name,
                                    fontSize = 14.sp,
                                    color = if (isSelected) colors.primary else colors.textPrimary
                                )
                            }
                            // 对于非自定义服务商，显示其 URL
                            if (provider.id != "custom") {
                                Text(
                                    text = provider.baseUrl,
                                    fontSize = 11.sp,
                                    color = colors.textHint,
                                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                )
                            }
                        }
                    }

                    // 自定义服务商或 MAI-UI 选中时显示 URL 输入框
                    if ((provider.id == "custom" || provider.id == "mai_ui") && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.backgroundInput)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (customUrl.isEmpty()) {
                                Text(
                                    text = if (provider.id == "mai_ui") "http://your-server:8000/v1" else "https://api.example.com/v1",
                                    color = colors.textHint,
                                    fontSize = 14.sp
                                )
                            }
                            BasicTextField(
                                value = customUrl,
                                onValueChange = { newUrl ->
                                    customUrl = newUrl
                                    onUpdateCustomUrl(newUrl)
                                },
                                textStyle = TextStyle(
                                    color = colors.textPrimary,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        Text(
                            text = if (provider.id == "mai_ui") "留空使用默认地址 (localhost:8000)" else "输入自定义 API 端点地址",
                            fontSize = 11.sp,
                            color = colors.textHint,
                            modifier = Modifier.padding(start = 28.dp, top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = colors.primary)
            }
        }
    )
}

/**
 * Root 模式警告对话框
 */
@Composable
fun RootModeWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "启用 Root 模式",
                color = colors.error,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Root 模式 = 在 Magisk / KernelSU 这类 Root 管理器里给「豆糕」授权，\n" +
                            "豆糕就拿到了 root 身份，可以**直接操作手机**（点按、滑动、截屏），\n" +
                            "不需要 Shizuku，也不需要无障碍。",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "注意：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("首次开启时，Root 管理器会弹窗问你要不要授权，选「允许」")
                BulletPoint("没弹窗 / 没授权的话，Root 模式实际上不会生效")
                BulletPoint("Root 权限很强，请只在你自己的设备上使用")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "已经授权过了？直接开启即可。",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error)
            ) {
                Text("我了解风险，启用", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

/**
 * su -c 命令警告对话框
 */
@Composable
fun SuCommandWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "允许 su -c 命令",
                color = colors.error,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "此选项将允许 AI 执行 su -c 命令，这意味着 AI 可以以 Root 权限执行任意 Shell 命令。",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "极度危险：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("AI 可能执行危险的系统命令")
                BulletPoint("可能导致数据丢失或系统损坏")
                BulletPoint("可能被恶意指令利用")
                BulletPoint("不建议在日常使用中启用")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "强烈建议：仅在完全可控的测试环境中使用，并在使用完毕后立即关闭。",
                    fontSize = 13.sp,
                    color = colors.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error)
            ) {
                Text("我了解风险，启用", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

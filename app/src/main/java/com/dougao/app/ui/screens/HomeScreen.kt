package com.dougao.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.agent.AgentPhase
import com.dougao.app.agent.AgentState
import com.dougao.app.data.ExecutionStep
import com.dougao.app.data.SessionMessage
import com.dougao.app.data.TaskSession
import com.dougao.app.data.ThinkingLevel
import com.dougao.app.ui.theme.BaoziTheme
import com.dougao.app.ui.theme.Primary
import com.dougao.app.ui.theme.Secondary

/**
 * 预设命令
 */
data class PresetCommand(
    val icon: String,
    val title: String,
    val command: String
)

val presetCommands = listOf(
    PresetCommand("🍔", "点汉堡", "帮我点个附近好吃的汉堡"),
    PresetCommand("📕", "发小红书", "帮我发一条小红书，内容是今日份好心情"),
    PresetCommand("📺", "刷B站", "打开B站搜索豆糕，找到第一个视频点个赞"),
    PresetCommand("✈️", "旅游攻略", "帮我查一下三亚旅游攻略"),
    PresetCommand("🎵", "听音乐", "打开网易云音乐播放每日推荐"),
    PresetCommand("🛒", "点外卖", "帮我在美团点一份猪脚饭")
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    agentState: AgentState?,
    logs: List<String>,
    onExecute: (String) -> Unit,
    onStop: () -> Unit,
    shizukuAvailable: Boolean,
    accessibilityConnected: Boolean = false,
    /** 豆糕自己有没有 root 权限（Root 模式已开且授权成功时为 true） */
    rootAvailable: Boolean = false,
    currentModel: String = "",
    onRefreshShizuku: () -> Unit = {},
    onShizukuRequired: () -> Unit = {},
    isExecuting: Boolean = false,
    // ---- 模型切换 + 思考程度 ----
    modelLabel: String = "",
    thinking: ThinkingLevel = ThinkingLevel.MEDIUM,
    onModelClick: () -> Unit = {},
    onThinkingClick: () -> Unit = {},
    /** 当前所在的任务房间（豆糕 1.3.0）；null = 还没发过消息的新任务 */
    session: TaskSession? = null,
    /** 点右上角「三条杠」打开任务抽屉 */
    onOpenSessions: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    var inputText by remember { mutableStateOf("") }
    // 无障碍 / Shizuku / Root 任一就绪即可执行
    val deviceReady = shizukuAvailable || accessibilityConnected || rootAvailable
    val isRunning = isExecuting || agentState?.isRunning == true
    val steps = agentState?.executionSteps ?: emptyList()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 记住最近一次发出去的指令，用于展示用户气泡
    var lastInstruction by remember { mutableStateOf("") }
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning) {
        if (wasRunning && !isRunning) {
            inputText = ""
        }
        wasRunning = isRunning
    }

    val sessionMessages = session?.messages ?: emptyList()
    val hasConversation = isRunning || steps.isNotEmpty() || sessionMessages.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        // ---------------- 顶部标题 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "豆糕",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = when {
                        // 在某个任务房间里时，副标题显示这个房间的名字
                        session != null && sessionMessages.isNotEmpty() ->
                            "当前任务：${session.title}"
                        !deviceReady -> "请先开启无障碍服务或连接 Shizuku"
                        accessibilityConnected && !shizukuAvailable -> "准备就绪（无障碍模式）"
                        shizukuAvailable && !accessibilityConnected -> "准备就绪（Shizuku 模式）"
                        else -> "准备就绪，告诉我你想做什么"
                    },
                    fontSize = 13.sp,
                    color = if (deviceReady) colors.textSecondary else colors.error,
                    maxLines = 1
                )
            }

            if (!deviceReady) {
                IconButton(
                    onClick = onRefreshShizuku,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundCard)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新 Shizuku 状态",
                        tint = colors.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // 三条杠：打开任务抽屉
            IconButton(
                onClick = onOpenSessions,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.backgroundCard)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "任务列表",
                    tint = colors.primary
                )
            }
        }

        // 内容区域
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (hasConversation) {
                SessionTimeline(
                    session = session,
                    fallbackInstruction = lastInstruction.ifBlank { agentState?.instruction ?: "" },
                    steps = steps,
                    isRunning = isRunning,
                    summary = agentState?.summary,
                    phase = agentState?.phase ?: AgentPhase.IDLE,
                    currentModel = currentModel,
                    logs = logs,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                PresetCommandsView(
                    onCommandClick = { command ->
                        if (deviceReady) {
                            inputText = command
                        } else {
                            onShizukuRequired()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AgentControlBar(
            modelLabel = modelLabel,
            thinking = thinking,
            onModelClick = onModelClick,
            onThinkingClick = onThinkingClick,
            enabled = !isRunning
        )

        InputArea(
            inputText = inputText,
            onInputChange = { inputText = it },
            onExecute = {
                if (inputText.isNotBlank()) {
                    lastInstruction = inputText
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onExecute(inputText)
                }
            },
            onStop = {
                inputText = ""
                onStop()
            },
            isRunning = isRunning,
            enabled = deviceReady,
            hint = if (sessionMessages.isNotEmpty()) {
                // 房间里已经有对话 —— 说明这条是「纠错」或者「追加要求」
                "继续说，让豆糕改正或接着做…"
            } else {
                "告诉豆糕你想做什么..."
            },
            stopLabel = if (sessionMessages.isNotEmpty()) "停止（停下后可以继续纠正它）" else "停止执行",
            onInputClick = {
                if (!deviceReady) {
                    onShizukuRequired()
                }
            }
        )
    }
}

// ======================================================================
// 任务房间：多轮对话 + 执行全景
// ======================================================================

/**
 * 一个任务房间的时间线。
 *
 * 和旧版的区别：旧版一次只能看「一条指令 + 一次执行」；
 * 这里把房间里**所有**说过的话按顺序铺出来，所以用户停掉 AI 之后
 * 往下接着说「你刚才做错了，应该这样」，整段历史都在，模型也拿得到。
 */
@Composable
fun SessionTimeline(
    session: TaskSession?,
    fallbackInstruction: String,
    steps: List<ExecutionStep>,
    isRunning: Boolean,
    summary: String?,
    phase: AgentPhase,
    currentModel: String,
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val messages = session?.messages ?: emptyList()
    val listState = rememberLazyListState()
    val showPanorama = isRunning || steps.isNotEmpty() ||
            summary != null || phase != AgentPhase.IDLE

    // 有新内容就滚到底部
    LaunchedEffect(messages.size, steps.size, isRunning) {
        val total = messages.size + (if (showPanorama) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 兜底：本轮的指令还没写进 session 时，用 agentState 里的补上
        if (messages.isEmpty() && fallbackInstruction.isNotBlank()) {
            item(key = "fallback-user") { UserBubble(fallbackInstruction) }
        }

        items(messages, key = { it.id }) { msg ->
            if (msg.isUser) {
                UserBubble(msg.text)
            } else {
                ResultBubble(msg)
            }
        }

        if (showPanorama) {
            item(key = "panorama") {
                ExecutionPanorama(
                    steps = steps,
                    isRunning = isRunning,
                    summary = summary,
                    phase = phase,
                    currentModel = currentModel,
                    logs = logs
                )
            }
        }

        item(key = "bottom-space") { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/** AI 的结果气泡（任务完成 / 已停止 / 出错） */
@Composable
private fun ResultBubble(msg: SessionMessage) {
    val colors = BaoziTheme.colors
    val isError = msg.kind == SessionMessage.KIND_ERROR
    val isResult = msg.kind == SessionMessage.KIND_RESULT
    val accent = if (isError) colors.error else colors.textSecondary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            ) {
                if (isError || isResult) {
                    Icon(
                        imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isError) colors.error else colors.success,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (isError) "豆糕 · 出错了" else "豆糕",
                    fontSize = 11.sp,
                    color = accent
                )
            }
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 4.dp,
                            bottomEnd = 16.dp
                        )
                    )
                    .background(colors.backgroundCard)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = msg.text,
                    fontSize = 14.sp,
                    color = if (isError) colors.error else colors.textPrimary,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
fun ExecutionConversation(
    instruction: String,
    steps: List<ExecutionStep>,
    isRunning: Boolean,
    summary: String?,
    phase: AgentPhase,
    currentModel: String,
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val listState = rememberLazyListState()

    // 运行中自动滚到底
    LaunchedEffect(steps.size, isRunning) {
        if (steps.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        reverseLayout = false
    ) {
        // ---- 用户消息气泡 ----
        if (instruction.isNotBlank()) {
            item {
                UserBubble(text = instruction)
            }
        }

        // ---- 执行全景 ----
        item {
            ExecutionPanorama(
                steps = steps,
                isRunning = isRunning,
                summary = summary,
                phase = phase,
                currentModel = currentModel,
                logs = logs
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun UserBubble(text: String) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 4.dp
                    )
                )
                .background(colors.backgroundCard)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                color = colors.textPrimary,
                lineHeight = 21.sp
            )
        }
    }
}

/**
 * 「执行全景」折叠卡
 *
 * 参考用户给的样式：一行标题（状态圆环 + 执行全景 + 展开箭头），
 * 下面跟着任务描述；点箭头展开可以看到每一步的思考和动作，跑完后给出总结。
 */
@Composable
fun ExecutionPanorama(
    steps: List<ExecutionStep>,
    isRunning: Boolean,
    summary: String?,
    phase: AgentPhase,
    currentModel: String,
    logs: List<String>
) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var showRawLogs by remember { mutableStateOf(false) }

    val running = isRunning || phase == AgentPhase.RUNNING
    val success = !running && phase == AgentPhase.SUCCESS
    val failed = !running && phase == AgentPhase.FAILED

    val accent = when {
        running -> colors.primary
        success -> colors.success
        failed -> colors.error
        else -> colors.textSecondary
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ---------- 标题行 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusOrb(running = running, success = success, failed = failed, accent = accent)

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "执行全景",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = when {
                            running -> "执行中 · 第 ${steps.size} 步"
                            success -> "已完成 · 共 ${steps.size} 步"
                            failed -> "未能完成 · 已执行 ${steps.size} 步"
                            else -> "共 ${steps.size} 步"
                        },
                        fontSize = 11.sp,
                        color = accent
                    )
                }

                if (currentModel.isNotBlank() && running) {
                    Text(
                        text = currentModel,
                        fontSize = 10.sp,
                        color = colors.textHint,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }

            // ---------- 展开区 ----------
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Divider(color = colors.surfaceVariant, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (steps.isEmpty()) {
                        Text(
                            text = if (running) "正在看屏幕，稍等一下…" else "暂无步骤",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                    } else {
                        steps.forEachIndexed { index, step ->
                            StepTimelineRow(step = step, isLast = index == steps.lastIndex)
                        }
                    }

                    // 原始日志（给需要排查的人看）
                    if (logs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (showRawLogs) "收起原始日志" else "查看原始日志",
                            fontSize = 11.sp,
                            color = colors.primary,
                            modifier = Modifier.clickable { showRawLogs = !showRawLogs }
                        )
                        if (showRawLogs) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.backgroundInput)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                logs.takeLast(60).forEach { line ->
                                    Text(
                                        text = line,
                                        fontSize = 10.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 总结 ----------
            if (!running && summary != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (success) colors.success.copy(alpha = 0.08f)
                                else colors.error.copy(alpha = 0.08f)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (success) "✅ 总结" else "⚠️ 结果",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (success) colors.success else colors.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = colors.textPrimary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/** 状态圆环：运行中转圈，成功打勾，失败叉 */
@Composable
private fun StatusOrb(running: Boolean, success: Boolean, failed: Boolean, accent: Color) {
    val colors = BaoziTheme.colors
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            running -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = accent,
                strokeWidth = 2.dp
            )
            success -> {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.success),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            failed -> {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.error),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            else -> Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(2.dp, colors.surfaceVariant, CircleShape)
            )
        }
    }
}

/** 单步时间线：左边是状态点 + 竖线，右边是动作和人话描述，展开可看思考 */
@Composable
private fun StepTimelineRow(step: ExecutionStep, isLast: Boolean) {
    val colors = BaoziTheme.colors
    var showThought by remember { mutableStateOf(false) }

    val (dotColor, dotText) = when (step.outcome) {
        "成功" -> colors.success to "✓"
        "失败" -> colors.error to "!"
        "已取消" -> colors.textHint to "–"
        else -> colors.primary to "•"
    }

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 左列
        Column(
            modifier = Modifier.width(20.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor),
                contentAlignment = Alignment.Center
            ) {
                Text(dotText, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(colors.surfaceVariant)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${step.stepNumber}. ${step.description}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (step.outcome != "进行中" && step.outcome.isNotBlank()) {
                    Text(
                        text = step.outcome,
                        fontSize = 10.sp,
                        color = dotColor
                    )
                }
            }

            if (step.thought.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (showThought) "收起思考" else "展开思考",
                    fontSize = 10.sp,
                    color = colors.primary,
                    modifier = Modifier.clickable { showThought = !showThought }
                )
                if (showThought) {
                    Text(
                        text = step.thought,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.backgroundInput)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

// ======================================================================
// 预设命令
// ======================================================================

@Composable
fun PresetCommandsView(
    onCommandClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "试试这些指令",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        presetCommands.chunked(2).forEach { rowCommands ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowCommands.forEach { preset ->
                    PresetCommandCard(
                        preset = preset,
                        onClick = { onCommandClick(preset.command) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowCommands.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun PresetCommandCard(
    preset: PresetCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = preset.icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = preset.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = preset.command,
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

// ======================================================================
// 输入区
// ======================================================================

@Composable
fun InputArea(
    inputText: String,
    onInputChange: (String) -> Unit,
    onExecute: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    enabled: Boolean,
    onInputClick: () -> Unit = {},
    /** 输入框里的提示文字 */
    hint: String = "告诉豆糕你想做什么...",
    /** 停止按钮上的文字 */
    stopLabel: String = "停止执行"
) {
    val colors = BaoziTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.backgroundCard,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isRunning) Arrangement.Center else Arrangement.Start
        ) {
            if (isRunning) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "停止",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stopLabel,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.backgroundInput)
                        .then(
                            if (!enabled) Modifier.clickable { onInputClick() } else Modifier
                        )
                        .padding(horizontal = 20.dp, vertical = 13.dp)
                ) {
                    if (enabled) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (inputText.isEmpty()) {
                                        Text(
                                            text = hint,
                                            color = colors.textHint,
                                            fontSize = 15.sp,
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    } else {
                        Text(
                            text = "请先连接 Shizuku",
                            color = colors.textHint,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                IconButton(
                    onClick = onExecute,
                    enabled = enabled && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (inputText.isNotBlank() && enabled) colors.primary
                            else colors.backgroundInput
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

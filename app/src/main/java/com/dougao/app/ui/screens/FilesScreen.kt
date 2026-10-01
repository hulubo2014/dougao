package com.dougao.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.agent.FileAgentState
import com.dougao.app.agent.FileStep
import com.dougao.app.data.ThinkingLevel
import com.dougao.app.data.WorkspaceEntry
import com.dougao.app.files.FileGateway
import com.dougao.app.files.FileNode
import com.dougao.app.ui.theme.BaoziTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件工作区（豆糕新增）
 *
 * 和"看屏幕操作"完全无关：这里是直接读文件、直接改文件。
 * 用户先选一个文件夹或一个文件，然后一句话告诉 AI 要干什么。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FilesScreen(
    entries: List<WorkspaceEntry>,
    activeEntry: WorkspaceEntry?,
    onSelectEntry: (WorkspaceEntry) -> Unit,
    onRemoveEntry: (WorkspaceEntry) -> Unit,
    onAddEntry: (Uri, Boolean) -> Unit,
    agentState: FileAgentState?,
    logs: List<String>,
    pendingConfirm: String?,
    onConfirm: (Boolean) -> Unit,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    modelLabel: String,
    thinking: ThinkingLevel,
    onModelClick: () -> Unit,
    onThinkingClick: () -> Unit,
    isRunning: Boolean
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val gateway = remember { FileGateway(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var previewNode by remember { mutableStateOf<FileNode?>(null) }
    var inputText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    // 切换工作区时回到根目录
    LaunchedEffect(activeEntry?.uri) {
        currentPath = ""
        showLogs = false
    }

    // 开始执行时自动切到日志视图
    LaunchedEffect(isRunning) {
        if (isRunning) showLogs = true
    }

    // 读取目录内容
    LaunchedEffect(activeEntry?.uri, currentPath) {
        val entry = activeEntry
        if (entry == null) {
            nodes = emptyList()
            return@LaunchedEffect
        }
        loading = true
        nodes = withContext(Dispatchers.IO) {
            try {
                gateway.list(entry, currentPath)
            } catch (e: Exception) {
                emptyList()
            }
        }
        loading = false
    }

    // ---------------- 系统文件选择器 ----------------
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            onAddEntry(uri, true)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            onAddEntry(uri, false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        // ---------------- 顶部标题 ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Column {
                Text(
                    text = "文件",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "选好文件夹或文件，让豆糕直接帮你读写，不用操作屏幕",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }

        // ---------------- 工作区选择 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallActionButton("添加文件夹", Icons.Default.Add) {
                try {
                    folderPicker.launch(null)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "无法打开文件选择器", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            SmallActionButton("添加文件", Icons.Default.Add) {
                try {
                    filePicker.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "无法打开文件选择器", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (entries.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.uri }) { entry ->
                    val selected = entry.uri == activeEntry?.uri
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) colors.primary
                                else colors.backgroundCard
                            )
                            .clickable { onSelectEntry(entry) }
                            .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (entry.isDirectory) "📁 " else "📄 ") + entry.displayName,
                            color = if (selected) Color.White else colors.textPrimary,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { onRemoveEntry(entry) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除",
                                tint = if (selected) Color.White.copy(alpha = 0.8f) else colors.textHint,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 文件列表 ----------------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            when {
                activeEntry == null -> EmptyHint(
                    title = "还没有选择文件或文件夹",
                    subtitle = "点击上方「添加文件夹」，选一个目录授权给豆糕。\n之后它就能直接读里面的内容、直接改，完全不用碰屏幕。"
                )

                showLogs && (isRunning || logs.isNotEmpty() || agentState?.summary != null) -> {
                    FileRunPanel(
                        state = agentState,
                        logs = logs,
                        onBack = { showLogs = false }
                    )
                }

                activeEntry.isDirectory -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 面包屑
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentPath.isNotEmpty()) {
                                Text(
                                    "⬅ 返回上级",
                                    color = colors.primary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        currentPath = currentPath.substringBeforeLast('/', "")
                                    }
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                "/" + currentPath,
                                color = colors.textHint,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                // 手动刷新（放到后台线程，避免大目录卡住界面）
                                scope.launch {
                                    loading = true
                                    nodes = withContext(Dispatchers.IO) {
                                        try {
                                            gateway.list(activeEntry, currentPath)
                                        } catch (e: Exception) {
                                            emptyList()
                                        }
                                    }
                                    loading = false
                                }
                            }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (loading) {
                            Text(
                                "加载中...",
                                color = colors.textHint,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        } else if (nodes.isEmpty()) {
                            Text(
                                "（空目录）",
                                color = colors.textHint,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp)
                        ) {
                            items(nodes, key = { it.uri.toString() }) { node ->
                                FileRow(
                                    node = node,
                                    onClick = {
                                        if (node.isDirectory) {
                                            currentPath = node.relativePath
                                        } else {
                                            previewNode = node
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                else -> {
                    // 单个文件
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📄", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            activeEntry.displayName,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(12.dp))
                        SmallActionButton("查看 / 编辑内容", Icons.Default.Send) {
                            previewNode = FileNode(
                                uri = Uri.parse(activeEntry.uri),
                                name = activeEntry.displayName,
                                isDirectory = false,
                                size = 0,
                                lastModified = 0,
                                relativePath = ""
                            )
                        }
                    }
                }
            }
        }

        // ---------------- 输入区 ----------------
        if (activeEntry != null) {
            AgentControlBar(
                modelLabel = modelLabel,
                thinking = thinking,
                onModelClick = onModelClick,
                onThinkingClick = onThinkingClick,
                enabled = !isRunning
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colors.backgroundInput)
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            "告诉豆糕要对这些文件做什么…",
                            color = colors.textHint,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconButton(
                    onClick = {
                        if (isRunning) {
                            onStop()
                        } else if (inputText.isNotBlank()) {
                            val text = inputText
                            inputText = ""   // 发出去之后输入框立刻清空，不要还留在那里
                            keyboard?.hide()
                            onRun(text)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) colors.error
                            else if (inputText.isNotBlank()) colors.primary
                            else colors.backgroundInput
                        )
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.Default.Send,
                        contentDescription = if (isRunning) "停止" else "发送",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // ---------------- 删除确认 ----------------
    if (pendingConfirm != null) {
        AlertDialog(
            onDismissRequest = { onConfirm(false) },
            containerColor = colors.backgroundCard,
            title = { Text("需要你确认", color = colors.textPrimary) },
            text = { Text(pendingConfirm!!, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { onConfirm(true) }) {
                    Text("允许", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onConfirm(false) }) {
                    Text("拒绝", color = colors.textSecondary)
                }
            }
        )
    }

    // ---------------- 文件预览 / 编辑 ----------------
    previewNode?.let { node ->
        FilePreviewDialog(
            gateway = gateway,
            node = node,
            onDismiss = { previewNode = null },
            onSaved = {
                val pageEntry = activeEntry
                if (pageEntry != null) {
                    scope.launch {
                        nodes = withContext(Dispatchers.IO) {
                            try {
                                gateway.list(pageEntry, currentPath)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🗂️", fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = colors.textSecondary, fontSize = 13.sp)
    }
}

/**
 * 文件助手的执行面板
 *
 * 参照 DeepSeek「深度思考」的交互：
 * - 执行中顶部只显示一行「操作中 ▸」，点一下就能展开
 * - 展开后逐条列出模型每一步的思考、调用了哪个工具、结果是什么
 * - 任务结束后在下方给出总结
 */
@Composable
private fun FileRunPanel(
    state: FileAgentState?,
    logs: List<String>,
    onBack: () -> Unit
) {
    val colors = BaoziTheme.colors
    val running = state?.isRunning == true
    val completed = state?.isCompleted == true
    val steps = state?.steps.orEmpty()
    val summary = state?.summary
    val errorMessage = state?.errorMessage
    val instruction = state?.instruction.orEmpty()
    val elapsedMs = state?.elapsedMs ?: 0L

    var expanded by remember { mutableStateOf(true) }
    var showRawLogs by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(steps.size, running, expanded) {
        if (expanded && steps.isNotEmpty()) {
            // 指令气泡（可选）+ 折叠标题 + 每一步，最后一个步骤卡片的下标是 steps.size 再加偏移
            val offset = if (instruction.isNotBlank()) 1 else 0
            val target = offset + steps.size
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "← 返回文件列表",
                color = colors.primary,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onBack)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 用户发出去的指令
            if (instruction.isNotBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = instruction,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 可折叠的标题行
            item {
                FoldHeader(
                    running = running,
                    completed = completed,
                    failed = errorMessage != null || (!running && !completed && steps.isNotEmpty()),
                    stepCount = steps.size,
                    elapsedMs = elapsedMs,
                    expanded = expanded,
                    onToggle = { expanded = !expanded }
                )
            }

            if (expanded) {
                if (steps.isEmpty() && running) {
                    item { ThinkHint("正在思考…") }
                }

                items(steps) { step ->
                    StepCard(step)
                }

                item {
                    Text(
                        text = if (showRawLogs) "收起原始日志" else "查看原始日志",
                        color = colors.textHint,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { showRawLogs = !showRawLogs }
                            .padding(vertical = 4.dp)
                    )
                }

                if (showRawLogs) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            fontSize = 11.sp,
                            color = when {
                                log.contains("❌") -> colors.error
                                log.contains("✅") -> colors.success
                                log.contains("🔧") -> colors.primary
                                else -> colors.textHint
                            }
                        )
                    }
                }
            }

            if (!running) {
                if (!summary.isNullOrBlank()) {
                    item { SummaryCard(summary, isError = false) }
                }
                if (!errorMessage.isNullOrBlank()) {
                    item { SummaryCard(errorMessage, isError = true) }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * 折叠标题：「操作中 ▾」/「已完成 ▸」，点一下切换展开收起
 */
@Composable
private fun FoldHeader(
    running: Boolean,
    completed: Boolean,
    failed: Boolean,
    stepCount: Int,
    elapsedMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = BaoziTheme.colors
    val title = when {
        running -> "操作中"
        completed -> "已完成"
        failed -> "已停止"
        else -> "已结束"
    }
    val tint = when {
        running -> colors.primary
        completed -> colors.success
        else -> colors.error
    }
    val icon = when {
        running -> "🔄"
        completed -> "✅"
        else -> "⏹"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.backgroundCard)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = buildString {
                    append("已执行 $stepCount 步")
                    if (elapsedMs > 0) append(" · 耗时 ${formatElapsed(elapsedMs)}")
                    if (!expanded) append(" · 点这里展开看过程")
                },
                color = colors.textHint,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Text(
            text = if (expanded) "▾" else "▸",
            color = colors.textSecondary,
            fontSize = 14.sp
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return if (totalSec < 60) "${totalSec} 秒" else "${totalSec / 60} 分 ${totalSec % 60} 秒"
}

/**
 * 单步卡片：思考 + 工具调用 + 结果
 */
@Composable
private fun StepCard(step: FileStep) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundCard)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "第 ${step.index} 步",
                color = colors.textHint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(toolLabel(step.tool), color = colors.primary, fontSize = 12.sp)
        }

        if (step.thought.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = step.thought,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        if (step.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "📄 ${step.detail}",
                color = colors.textHint,
                fontSize = 11.sp,
                maxLines = 2
            )
        }

        if (step.result.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = (if (step.failed) "⚠️ " else "↳ ") + step.result,
                color = if (step.failed) colors.error else colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 4
            )
        }
    }
}

private fun toolLabel(tool: String): String = when (tool) {
    "list_files" -> "查看目录"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "append_file" -> "追加内容"
    "edit_file" -> "修改内容"
    "create_file" -> "新建文件"
    "delete_file" -> "删除文件"
    "rename_file" -> "重命名"
    "search_files" -> "搜索"
    else -> tool
}

@Composable
private fun SummaryCard(text: String, isError: Boolean) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isError) colors.error.copy(alpha = 0.10f)
                else colors.primary.copy(alpha = 0.10f)
            )
            .padding(14.dp)
    ) {
        Text(
            text = if (isError) "出错了" else "总结",
            color = if (isError) colors.error else colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(text, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun ThinkHint(text: String) {
    val colors = BaoziTheme.colors
    Text(
        text = text,
        color = colors.textHint,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun SmallActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.primary.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = colors.primary, fontSize = 13.sp)
    }
}

@Composable
private fun FileRow(node: FileNode, onClick: () -> Unit) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (node.isDirectory) "📁" else iconFor(node),
            fontSize = 18.sp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                node.name,
                color = colors.textPrimary,
                fontSize = 14.sp,
                maxLines = 1
            )
            if (!node.isDirectory) {
                Text(
                    humanSize(node.size),
                    color = colors.textHint,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun iconFor(node: FileNode): String = when (node.extension) {
    "txt", "md", "markdown", "log", "srt" -> "📝"
    "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "properties", "toml", "env" -> "⚙️"
    "csv", "tsv", "xlsx", "xls" -> "📊"
    "docx", "doc" -> "📘"
    "pptx", "ppt" -> "📙"
    "pdf" -> "📕"
    "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "🖼️"
    "mp3", "wav", "flac", "m4a" -> "🎵"
    "mp4", "mkv", "avi", "mov" -> "🎬"
    "zip", "rar", "7z", "tar", "gz", "apk" -> "📦"
    in setOf("kt", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "html", "css", "sh", "sql") -> "💻"
    else -> "📄"
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 文件内容预览 + 手动编辑
 */
@Composable
private fun FilePreviewDialog(
    gateway: FileGateway,
    node: FileNode,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var edited by remember { mutableStateOf("") }

    LaunchedEffect(node.uri) {
        loading = true
        val text = withContext(Dispatchers.IO) {
            try {
                val ext = node.extension
                when {
                    ext in setOf("docx", "xlsx", "pptx") -> gateway.readDocumentText(node.uri, node.name)
                    else -> gateway.readText(node.uri, maxChars = 60_000)
                }
            } catch (e: Exception) {
                null
            }
        }
        content = text
        edited = text ?: ""
        loading = false
    }

    val editable = content != null && node.isTextLike

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Column {
                Text(node.name, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    if (editable) "可编辑，改完点保存" else "只读预览",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.backgroundInput)
                    .padding(12.dp)
            ) {
                when {
                    loading -> Text("读取中...", color = colors.textHint, fontSize = 13.sp)
                    content == null -> Text(
                        "无法读取这个文件（可能是二进制格式或不支持的类型）。",
                        color = colors.textHint,
                        fontSize = 13.sp
                    )
                    editable -> BasicTextField(
                        value = edited,
                        onValueChange = { edited = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier
                            .fillMaxSize()
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(content!!, color = colors.textPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editable) {
                TextButton(onClick = {
                    val ok = gateway.writeText(node.uri, edited)
                    android.widget.Toast.makeText(
                        context, if (ok) "已保存" else "保存失败", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onSaved()
                    onDismiss()
                }) { Text("保存", color = colors.primary) }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭", color = colors.primary) }
            }
        },
        dismissButton = {
            if (editable) {
                TextButton(onClick = onDismiss) { Text("取消", color = colors.textSecondary) }
            }
        }
    )
}

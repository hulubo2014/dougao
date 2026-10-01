package com.dougao.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.data.ExecutionRecord
import com.dougao.app.data.ExecutionStatus
import com.dougao.app.data.ExecutionStep
import com.dougao.app.ui.theme.BaoziTheme
import com.dougao.app.ui.theme.Primary
import com.dougao.app.ui.theme.Secondary

@Composable
fun HistoryScreen(
    records: List<ExecutionRecord>,
    onRecordClick: (ExecutionRecord) -> Unit,
    onDeleteRecord: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    text = "执行记录",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "共 ${records.size} 条记录",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        if (records.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📝",
                        fontSize = 64.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无执行记录",
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "执行任务后记录会显示在这里",
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                }
            }
        } else {
            // 记录列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = records,
                    key = { it.id }
                ) { record ->
                    HistoryRecordCard(
                        record = record,
                        onClick = { onRecordClick(record) },
                        onDelete = { onDeleteRecord(record.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRecordCard(
    record: ExecutionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = BaoziTheme.colors
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = { Text("删除记录", color = colors.textPrimary) },
            text = { Text("确定要删除这条执行记录吗？", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when (record.status) {
                            ExecutionStatus.COMPLETED -> colors.success.copy(alpha = 0.2f)
                            ExecutionStatus.FAILED -> colors.error.copy(alpha = 0.2f)
                            ExecutionStatus.STOPPED -> colors.warning.copy(alpha = 0.2f)
                            ExecutionStatus.RUNNING -> colors.primary.copy(alpha = 0.2f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (record.status) {
                        ExecutionStatus.COMPLETED -> Icons.Default.CheckCircle
                        ExecutionStatus.FAILED -> Icons.Default.Warning
                        ExecutionStatus.STOPPED -> Icons.Default.PlayArrow
                        ExecutionStatus.RUNNING -> Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    tint = when (record.status) {
                        ExecutionStatus.COMPLETED -> colors.success
                        ExecutionStatus.FAILED -> colors.error
                        ExecutionStatus.STOPPED -> colors.warning
                        ExecutionStatus.RUNNING -> colors.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.instruction,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态标签
                    val (statusText, statusColor) = when (record.status) {
                        ExecutionStatus.COMPLETED -> "已完成" to colors.success
                        ExecutionStatus.FAILED -> "失败" to colors.error
                        ExecutionStatus.STOPPED -> "已取消" to colors.warning
                        ExecutionStatus.RUNNING -> "执行中" to colors.primary
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        modifier = Modifier
                            .background(
                                statusColor.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = record.formattedStartTime,
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "${record.steps.size}步",
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = record.formattedDuration,
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                }
            }

            // 删除按钮
            IconButton(
                onClick = { showDeleteDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = colors.textHint
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    record: ExecutionRecord,
    onBack: () -> Unit
) {
    val colors = BaoziTheme.colors
    // Tab 状态：0 = 时间线，1 = 日志
    var selectedTab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = record.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = record.formattedStartTime,
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.textPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background
            )
        )

        // 任务信息卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "任务指令",
                    fontSize = 12.sp,
                    color = colors.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.instruction,
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("状态", fontSize = 12.sp, color = colors.textHint)
                        Text(
                            text = when (record.status) {
                                ExecutionStatus.COMPLETED -> "已完成"
                                ExecutionStatus.FAILED -> "失败"
                                ExecutionStatus.STOPPED -> "已停止"
                                ExecutionStatus.RUNNING -> "执行中"
                            },
                            fontSize = 14.sp,
                            color = when (record.status) {
                                ExecutionStatus.COMPLETED -> colors.success
                                ExecutionStatus.FAILED -> colors.error
                                ExecutionStatus.STOPPED -> colors.warning
                                ExecutionStatus.RUNNING -> colors.primary
                            }
                        )
                    }
                    Column {
                        Text("步骤数", fontSize = 12.sp, color = colors.textHint)
                        Text("${record.steps.size}", fontSize = 14.sp, color = colors.textPrimary)
                    }
                    Column {
                        Text("耗时", fontSize = 12.sp, color = colors.textHint)
                        Text(record.formattedDuration, fontSize = 14.sp, color = colors.textPrimary)
                    }
                }
            }
        }

        // Tab 切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 时间线 Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 0) colors.primary
                        else colors.backgroundCard
                    )
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "执行时间线",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedTab == 0) Color.White else colors.textSecondary
                )
            }

            // 日志 Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 1) colors.primary
                        else colors.backgroundCard
                    )
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "执行日志",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedTab == 1) Color.White else colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 内容区域
        when (selectedTab) {
            0 -> {
                // 时间线列表
                if (record.steps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行步骤",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(record.steps) { step ->
                            TimelineItem(step = step, isLast = step == record.steps.lastOrNull())
                        }
                    }
                }
            }
            1 -> {
                // 日志列表
                if (record.logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行日志",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(record.logs) { log ->
                            val logColor = when {
                                log.contains("❌") -> colors.error
                                log.contains("✅") -> colors.success
                                log.contains("📋") || log.contains("🎬") -> colors.secondary
                                log.contains("Step") || log.contains("=====") -> colors.primary
                                log.contains("⛔") -> colors.error
                                else -> colors.textSecondary
                            }
                            Text(
                                text = log,
                                fontSize = 12.sp,
                                color = logColor,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineItem(
    step: ExecutionStep,
    isLast: Boolean
) {
    val colors = BaoziTheme.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        // 时间线指示器
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 圆点
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when (step.outcome) {
                            "A" -> colors.success
                            "B" -> colors.warning
                            "?" -> colors.textHint // 进行中被取消
                            else -> colors.error
                        }
                    )
            )
            // 连接线
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(80.dp)
                        .background(colors.backgroundInput)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 步骤内容
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Step ${step.stepNumber}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary
                    )
                    Text(
                        text = step.action,
                        fontSize = 12.sp,
                        color = colors.secondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = step.description,
                    fontSize = 13.sp,
                    color = colors.textPrimary
                )
                if (step.thought.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = step.thought,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

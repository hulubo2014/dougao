package com.dougao.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.data.SessionStatus
import com.dougao.app.data.TaskSession
import com.dougao.app.ui.theme.BaoziTheme

/**
 * 任务抽屉（首页右上角「三条杠」点开就是这个）。
 *
 * 一个任务 = 一个独立房间：
 *  - 顶部「新任务」随时开一个新房间；
 *  - 下面按「置顶 / 任务」两组列出所有房间，点一下就进去；
 *  - 每个房间可以置顶或删除。
 */
@Composable
fun SessionsDrawerContent(
    sessions: List<TaskSession>,
    activeSessionId: String?,
    onSelect: (TaskSession) -> Unit,
    onNewTask: () -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (TaskSession) -> Unit,
    onClearAll: () -> Unit
) {
    val colors = BaoziTheme.colors
    var menuTarget by remember { mutableStateOf<TaskSession?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val pinned = sessions.filter { it.pinned }
    val normal = sessions.filter { !it.pinned }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // ---------------- 顶部标题 ----------------
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
            Text(
                text = "任务",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "每个任务都是独立房间，可中断后继续纠错",
                fontSize = 12.sp,
                color = colors.textSecondary
            )
        }

        // ---------------- 新任务 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onNewTask() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "新任务",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---------------- 任务列表 ----------------
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有任务\n在首页说一句想做什么就开一个",
                    fontSize = 13.sp,
                    color = colors.textHint,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (pinned.isNotEmpty()) {
                    item { DrawerSectionHeader("置顶", pinned.size, colors.textSecondary) }
                    items(pinned, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            active = session.id == activeSessionId,
                            onClick = { onSelect(session) },
                            onMore = { menuTarget = session }
                        )
                    }
                }

                item { DrawerSectionHeader("任务", normal.size, colors.textSecondary) }
                items(normal, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        active = session.id == activeSessionId,
                        onClick = { onSelect(session) },
                        onMore = { menuTarget = session }
                    )
                }
            }
        }

        // ---------------- 底部：清空 ----------------
        if (sessions.isNotEmpty()) {
            Divider(color = colors.backgroundInput, thickness = 1.dp)
            TextButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = colors.textHint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("清空全部任务", fontSize = 13.sp, color = colors.textHint)
            }
        }
    }

    // ---------------- 单项菜单 ----------------
    val target = menuTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            containerColor = colors.backgroundCard,
            title = {
                Text(
                    text = target.title,
                    fontSize = 16.sp,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = { Text("要对这个任务做什么？", fontSize = 14.sp, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onTogglePin(target)
                    menuTarget = null
                }) {
                    Text(if (target.pinned) "取消置顶" else "置顶", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    menuTarget = null
                }) {
                    Text("删除", color = colors.error)
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = colors.backgroundCard,
            title = { Text("清空全部任务", fontSize = 17.sp, color = colors.textPrimary) },
            text = {
                Text(
                    "所有任务的对话记录都会被删掉，无法恢复。确定吗？",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearConfirm = false
                }) {
                    Text("清空", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun DrawerSectionHeader(title: String, count: Int, color: Color) {
    Text(
        text = "$title ($count)",
        fontSize = 12.sp,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun SessionRow(
    session: TaskSession,
    active: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.primary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (session.pinned) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "已置顶",
                        tint = colors.textHint,
                        modifier = Modifier.size(13.dp)
                    )
                }
                StatusDot(session.status)?.let { dotColor ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = session.formattedTime,
                fontSize = 11.sp,
                color = colors.textHint
            )
        }

        IconButton(
            onClick = onMore,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多",
                tint = colors.textHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 状态点：运行中绿、出错红、被停掉橙，正常结束/还没跑就不显示 */
@Composable
private fun StatusDot(status: SessionStatus): Color? {
    val colors = BaoziTheme.colors
    return when (status) {
        SessionStatus.RUNNING -> colors.success
        SessionStatus.FAILED -> colors.error
        SessionStatus.STOPPED -> Color(0xFFFF9800)
        SessionStatus.DONE, SessionStatus.IDLE -> null
    }
}

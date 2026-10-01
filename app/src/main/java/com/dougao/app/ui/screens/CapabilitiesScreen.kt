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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.tools.ToolManager
import com.dougao.app.ui.theme.BaoziTheme

/**
 * 工具信息（用于展示）
 */
data class ToolInfo(
    val name: String,
    val description: String
)

/**
 * Agent 角色信息
 */
data class AgentInfo(
    val name: String,
    val icon: String,
    val role: String,
    val description: String,
    val responsibilities: List<String>
)

/**
 * 预定义的 Agents 列表
 */
val agentsList = listOf(
    AgentInfo(
        name = "感知",
        icon = "👁",
        role = "看屏幕",
        description = "通过 Shizuku 直接抓取屏幕画面，一步到位。",
        responsibilities = listOf(
            "用 screencap 直读屏幕原始数据，不落盘、不踩权限",
            "自动识别当前打开的是哪个 App",
            "把画面压到合适大小再交给模型，又快又省",
            "上一步刚拍过的画面直接复用，省掉一半截图"
        )
    ),
    AgentInfo(
        name = "决策",
        icon = "🧠",
        role = "想一步做一步",
        description = "一次模型调用同时输出「为什么这么做」和「下一步做什么」。",
        responsibilities = listOf(
            "看懂屏幕，判断当前处于任务的哪一环",
            "输出一句简短的推理（会在执行全景里展示）",
            "给出一个明确动作：点击 / 输入 / 滑动 / 打开应用 / 返回 / 等待",
            "确认任务真的做完了才调用 finish"
        )
    ),
    AgentInfo(
        name = "执行",
        icon = "⚡",
        role = "落地动作",
        description = "把模型给的动作变成真实的手机操作。",
        responsibilities = listOf(
            "点击、滑动、长按、双击、输入、按键",
            "中文输入走剪贴板通道，不会变成乱码",
            "打开 App 三级兜底，国产 ROM 也能拉起",
            "等待时间按动作类型自适应，不再死等"
        )
    ),
    AgentInfo(
        name = "记忆",
        icon = "📝",
        role = "管上下文",
        description = "控制发给模型的历史长度，保证长任务也不跑偏。",
        responsibilities = listOf(
            "用完的截图立刻从历史里剥掉，只留文字",
            "只保留最近若干轮对话，防止上下文爆炸",
            "把每一步的思考和结果记录下来，供执行全景观看"
        )
    )
)

/**
 * 能力展示页面
 *
 * 展示 Agents 和 Tools（只读）
 */
@Composable
fun CapabilitiesScreen() {
    val colors = BaoziTheme.colors

    // 获取 Tools
    val tools = remember {
        if (ToolManager.isInitialized()) {
            ToolManager.getInstance().getAvailableTools().map { tool ->
                ToolInfo(name = tool.name, description = tool.description)
            }
        } else {
            emptyList()
        }
    }

    // 额外的内置工具（不在 ToolManager 中但是系统能力）
    val builtInTools = listOf(
        ToolInfo("screenshot", "截取当前屏幕，获取界面图像供 AI 分析"),
        ToolInfo("tap", "点击屏幕指定坐标位置"),
        ToolInfo("swipe", "在屏幕上滑动，支持上下左右方向"),
        ToolInfo("type", "输入文本内容到当前焦点位置"),
        ToolInfo("press_key", "按下系统按键（Home、Back、Enter 等）")
    )

    val allTools = tools + builtInTools

    // Tab 状态
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Agents (${agentsList.size})", "Tools (${allTools.size})")

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
                    text = "能力",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "${agentsList.size} 个 Agent，${allTools.size} 个工具",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        // Tab 切换
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) colors.primary else colors.textSecondary
                        )
                    }
                )
            }
        }

        // 内容区域
        when (selectedTab) {
            0 -> AgentsListView()
            1 -> ToolsListView(tools = allTools)
        }
    }
}

@Composable
fun AgentsListView() {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 架构说明卡片
        item(key = "arch_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🧠 多 Agent 协作架构",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "豆糕采用「一步一决策」的执行范式：看一眼屏幕、想一步、做一个动作，循环推进，直到任务完成。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "感知 → 决策 → 执行 → 记忆",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textHint
                    )
                }
            }
        }

        // Agent 列表
        items(agentsList, key = { it.name }) { agent ->
            AgentCard(agent = agent)
        }
    }
}

@Composable
fun AgentCard(agent: AgentInfo) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Agent 图标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.icon,
                        fontSize = 28.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.secondary.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = agent.role,
                                fontSize = 11.sp,
                                color = colors.secondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textHint
                )
            }

            // 展开显示职责列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "职责",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    agent.responsibilities.forEach { responsibility ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                fontSize = 14.sp,
                                color = colors.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = responsibility,
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsListView(tools: List<ToolInfo>) {
    if (tools.isEmpty()) {
        EmptyState(message = "暂无工具")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                ToolCard(tool = tool)
            }
        }
    }
}

@Composable
fun ToolCard(tool: ToolInfo) {
    val colors = BaoziTheme.colors

    // 根据工具名获取图标
    val toolIcon = when (tool.name) {
        "search_apps" -> "🔍"
        "open_app" -> "📱"
        "deep_link" -> "🔗"
        "clipboard" -> "📋"
        "shell" -> "💻"
        "http" -> "🌐"
        else -> "🔧"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 工具图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.secondary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = toolIcon,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyState(message: String) {
    val colors = BaoziTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📦",
                fontSize = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = colors.textSecondary
            )
        }
    }
}

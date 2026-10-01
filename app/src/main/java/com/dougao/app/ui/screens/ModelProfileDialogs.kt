package com.dougao.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougao.app.data.ApiProvider
import com.dougao.app.data.ModelProfile
import com.dougao.app.data.ThinkingLevel
import com.dougao.app.ui.theme.BaoziTheme

/**
 * 通用输入框（跟随豆糕主题）
 */
@Composable
fun DougaoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    val colors = BaoziTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundInput)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, color = colors.textHint, fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.primary),
            singleLine = singleLine,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ======================================================================
// 模型管理（设置页入口）
// ======================================================================

/**
 * 模型列表对话框：管理已添加的多个模型
 */
@Composable
fun ModelProfileListDialog(
    profiles: List<ModelProfile>,
    activeProfileId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ModelProfile) -> Unit,
    onDelete: (ModelProfile) -> Unit
) {
    val colors = BaoziTheme.colors
    var profileToDelete by remember { mutableStateOf<ModelProfile?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("模型管理", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "已添加 ${profiles.size} 个模型，输入框处可随时切换",
                        color = colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "添加模型", tint = colors.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (profiles.isEmpty()) {
                    Text(
                        "还没有添加模型。\n点击右上角「＋」添加你的第一个模型。",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(profiles, key = { it.id }) { profile ->
                            val isActive = profile.id == activeProfileId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onSelect(profile.id) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) colors.primary.copy(alpha = 0.15f)
                                    else colors.backgroundInput
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isActive) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = colors.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(
                                                profile.name,
                                                color = if (isActive) colors.primary else colors.textPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        }
                                        Text(
                                            "${profile.provider.name} · ${profile.effectiveModel()}",
                                            color = colors.textSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(onClick = { onEdit(profile) }) {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = "编辑",
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(onClick = { profileToDelete = profile }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除",
                                            tint = colors.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
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

    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            containerColor = colors.backgroundCard,
            title = { Text("删除模型", color = colors.textPrimary) },
            text = {
                Text(
                    "确定删除「${target.name}」吗？",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    profileToDelete = null
                }) { Text("删除", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }
}

/**
 * 添加 / 编辑模型
 *
 * 保留豆糕原有的「选服务商 → 填 API Key → 选模型」流程，只是可以重复添加多条。
 */
@Composable
fun AddEditModelDialog(
    initial: ModelProfile?,
    onDismiss: () -> Unit,
    onSave: (name: String, providerId: String, model: String, apiKey: String, baseUrl: String) -> Unit,
    onFetchModels: ((
        providerId: String,
        apiKey: String,
        baseUrl: String,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) -> Unit)? = null
) {
    val colors = BaoziTheme.colors

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var providerId by remember { mutableStateOf(initial?.providerId ?: ApiProvider.ALIYUN.id) }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var modelName by remember { mutableStateOf(initial?.model ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var cachedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    val provider = ApiProvider.ALL.find { it.id == providerId } ?: ApiProvider.CUSTOM
    val needApiKey = providerId != "mai_ui"
    val canEditUrl = providerId == "custom" || providerId == "mai_ui"

    val filtered = remember(cachedModels, search) {
        if (search.isBlank()) cachedModels
        else cachedModels.filter { it.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text(
                if (initial == null) "添加模型" else "编辑模型",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    // ---- 服务商 ----
                    item {
                        FieldLabel("API 服务商")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ApiProvider.ALL.forEach { p ->
                                val selected = p.id == providerId
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (selected) colors.primary else colors.backgroundInput
                                        )
                                        .clickable {
                                            providerId = p.id
                                            if (p.id != "custom") baseUrl = ""
                                            if (p.id == "mai_ui") statusText = "本地部署无需 API Key"
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        p.name,
                                        color = if (selected) androidx.compose.ui.graphics.Color.White
                                        else colors.textSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    // ---- 配置名称 ----
                    item {
                        FieldLabel("配置名称（可留空自动生成）")
                        DougaoTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "例如：通义千问 VL",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- API Key ----
                    item {
                        FieldLabel(if (needApiKey) "API Key" else "API Key（本地部署可留空）")
                        DougaoTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = if (needApiKey) "请输入 API Key" else "本地部署无需填写",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- Base URL ----
                    if (canEditUrl) {
                        item {
                            FieldLabel("接口地址 Base URL")
                            DougaoTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                placeholder = if (providerId == "mai_ui") "留空使用 localhost:8000" else "https://your-api.com/v1",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    // ---- 模型名 ----
                    item {
                        FieldLabel("模型")
                        DougaoTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            placeholder = "例如 ${provider.defaultModel.ifBlank { "gpt-4o" }}",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.backgroundInput)
                                    .clickable(enabled = !fetching && onFetchModels != null) {
                                        if (needApiKey && apiKey.isBlank()) {
                                            statusText = "请先填写 API Key"
                                            return@clickable
                                        }
                                        fetching = true
                                        statusText = "正在获取模型列表..."
                                        onFetchModels?.invoke(
                                            providerId,
                                            apiKey,
                                            baseUrl,
                                            { list ->
                                                cachedModels = list
                                                fetching = false
                                                statusText = "获取到 ${list.size} 个模型"
                                                if (modelName.isBlank()) {
                                                    modelName = list.firstOrNull { it == provider.defaultModel }
                                                        ?: list.firstOrNull() ?: ""
                                                }
                                            },
                                            { err ->
                                                fetching = false
                                                statusText = "获取失败: $err"
                                            }
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (fetching) "获取中..." else "从 API 获取可用模型",
                                    color = colors.primary,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            if (statusText.isNotEmpty()) {
                                Text(statusText, color = colors.textSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    // ---- 模型列表 ----
                    if (cachedModels.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            FieldLabel("API 模型列表 (${cachedModels.size})")
                            DougaoTextField(
                                value = search,
                                onValueChange = { search = it },
                                placeholder = "搜索模型...",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        items(filtered.take(200)) { m ->
                            val selected = m == modelName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) colors.primary.copy(alpha = 0.15f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable { modelName = m }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    m,
                                    color = if (selected) colors.primary else colors.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    name.ifBlank { ModelProfile.defaultName(provider, modelName) },
                    providerId,
                    modelName.ifBlank { provider.defaultModel },
                    apiKey,
                    baseUrl
                )
            }) {
                Text("保存", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

@Composable
private fun FieldLabel(text: String) {
    val colors = BaoziTheme.colors
    Text(
        text = text,
        color = colors.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

// ======================================================================
// 输入框处：模型切换 + 思考程度
// ======================================================================

@Composable
fun ModelPickerDialog(
    profiles: List<ModelProfile>,
    activeProfileId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择模型", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (profiles.isEmpty()) {
                    Text(
                        "还没有添加模型，请先到「设置 → 模型管理」添加。",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(profiles, key = { it.id }) { p ->
                            val selected = p.id == activeProfileId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) colors.primary.copy(alpha = 0.15f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable {
                                        onSelect(p.id)
                                        onDismiss()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        p.name,
                                        color = if (selected) colors.primary else colors.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        p.effectiveModel(),
                                        color = colors.textSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onManage()
            }) { Text("管理模型", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

/**
 * 思考程度选择：低 / 中 / 高
 */
@Composable
fun ThinkingLevelDialog(
    current: ThinkingLevel,
    onSelect: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("思考程度", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "越高越聪明，也越慢越贵。复杂任务建议用「高」。",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ThinkingLevel.values().forEach { level ->
                    val selected = level == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) colors.primary.copy(alpha = 0.15f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable {
                                onSelect(level)
                                onDismiss()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) colors.primary else colors.backgroundInput
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                level.label,
                                color = if (selected) colors.primary else colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(level.hint, color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.primary) }
        }
    )
}

/**
 * 输入框上方的小控件条：模型 + 思考程度
 */
@Composable
fun AgentControlBar(
    modelLabel: String,
    thinking: ThinkingLevel,
    onModelClick: () -> Unit,
    onThinkingClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 模型
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.backgroundInput)
                .clickable(enabled = enabled) { onModelClick() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("模型", color = colors.textHint, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                modelLabel.ifBlank { "未设置" },
                color = colors.textPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        // 思考程度
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.primary.copy(alpha = 0.15f))
                .clickable(enabled = enabled) { onThinkingClick() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("思考", color = colors.textHint, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                thinking.label,
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

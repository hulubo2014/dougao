package com.dougao.app.data

import org.json.JSONObject

/**
 * 思考程度 —— 从低到高
 *
 * 豆糕新增：用户在输入框处可直接调节模型的"思考程度"。
 * 不同模型对推理参数的支持不同，这里只做统一抽象，
 * 具体如何映射为 API 参数由 [com.dougao.app.vlm.VLMClient] 决定。
 */
enum class ThinkingLevel(val level: Int, val label: String, val hint: String) {
    LOW(0, "低", "快速响应"),
    MEDIUM(1, "中", "平衡"),
    HIGH(2, "高", "深度思考");

    companion object {
        fun fromLevel(level: Int): ThinkingLevel =
            values().firstOrNull { it.level == level } ?: MEDIUM

        fun next(current: ThinkingLevel): ThinkingLevel =
            when (current) {
                LOW -> MEDIUM
                MEDIUM -> HIGH
                HIGH -> LOW
            }
    }
}

/**
 * 模型配置档案 —— 豆糕新增的多模型支持
 *
 * 一条 [ModelProfile] 就是一个"可切换的模型"，
 * 自带服务商、API Key、模型名与可选的自定义地址。
 */
data class ModelProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val model: String,
    val apiKey: String = "",
    val baseUrl: String = ""
) {
    val provider: ApiProvider
        get() = ApiProvider.ALL.find { it.id == providerId } ?: ApiProvider.CUSTOM

    /** 该模型是否走 GUI Agent 专用协议 */
    val isGUIAgent: Boolean get() = provider.isGUIAgent

    /** 实际生效的 API 地址 */
    fun effectiveBaseUrl(): String = when {
        baseUrl.isNotBlank() -> baseUrl
        providerId == "custom" -> ""
        else -> provider.baseUrl
    }

    /** 实际生效的模型名 */
    fun effectiveModel(): String = model.ifBlank { provider.defaultModel }

    /**
     * 该配置是否需要 API Key（本地部署的 MAI-UI 不需要）
     */
    fun requiresApiKey(): Boolean = providerId != "mai_ui"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("providerId", providerId)
        put("model", model)
        put("baseUrl", baseUrl)
        // apiKey 不进普通存储，由 SettingsManager 写入加密存储
    }

    fun toJsonWithKey(apiKey: String): JSONObject = toJson().apply {
        put("apiKey", apiKey)
    }

    companion object {
        fun fromJson(obj: JSONObject, apiKey: String): ModelProfile? {
            val id = obj.optString("id", "")
            if (id.isBlank()) return null
            return ModelProfile(
                id = id,
                name = obj.optString("name", "未命名模型"),
                providerId = obj.optString("providerId", ApiProvider.ALIYUN.id),
                model = obj.optString("model", ""),
                apiKey = apiKey,
                baseUrl = obj.optString("baseUrl", "")
            )
        }

        /** 生成默认显示名，例如 "阿里云 · qwen3-vl-plus" */
        fun defaultName(provider: ApiProvider, model: String): String {
            val m = model.ifBlank { provider.defaultModel }
            return if (m.isBlank()) provider.name else "${provider.name} · $m"
        }
    }
}

/**
 * 文件工作区（豆糕新增）
 *
 * 用户通过"选择文件夹 / 选择文件"授权后，AI 可以不经由屏幕操作，
 * 直接读取与修改其中的内容。
 */
data class WorkspaceEntry(
    val uri: String,
    val displayName: String,
    val isDirectory: Boolean,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", displayName)
        put("dir", isDirectory)
        put("at", addedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorkspaceEntry? {
            val uri = obj.optString("uri", "")
            if (uri.isBlank()) return null
            return WorkspaceEntry(
                uri = uri,
                displayName = obj.optString("name", "未命名"),
                isDirectory = obj.optBoolean("dir", true),
                addedAt = obj.optLong("at", System.currentTimeMillis())
            )
        }
    }
}

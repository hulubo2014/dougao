package com.dougao.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dougao.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * API 提供商配置
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val isGUIAgent: Boolean = false  // 是否为 GUI Agent 专用协议（非 OpenAI 兼容）
) {
    companion object {
        val GUI_OWL = ApiProvider(
            id = "gui_owl",
            name = "GUI-Owl (阿里云)",
            baseUrl = "https://dashscope.aliyuncs.com/api/v2/apps/gui-owl/gui_agent_server",
            defaultModel = "pre-gui_owl_7b",
            isGUIAgent = true
        )
        val MAI_UI = ApiProvider(
            id = "mai_ui",
            name = "MAI-UI (本地部署)",
            baseUrl = "http://localhost:8000/v1",  // vLLM 默认地址
            defaultModel = "MAI-UI-2B"  // 支持 MAI-UI-2B 或 MAI-UI-8B
        )
        val ALIYUN = ApiProvider(
            id = "aliyun",
            name = "阿里云 (Qwen-VL)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3-vl-plus"
        )
        val OPENAI = ApiProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o"
        )
        val OPENROUTER = ApiProvider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "anthropic/claude-3.5-sonnet"
        )
        val CUSTOM = ApiProvider(
            id = "custom",
            name = "自定义",
            baseUrl = "",
            defaultModel = ""
        )

        val ALL = listOf(GUI_OWL, MAI_UI, ALIYUN, OPENAI, OPENROUTER, CUSTOM)
    }
}

/**
 * 服务商配置（每个服务商独立保存）
 */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val cachedModels: List<String> = emptyList(),
    val customBaseUrl: String = ""  // 仅 custom 服务商使用
)

/**
 * 默认推荐模型
 */
const val DEFAULT_MODEL = "qwen3-vl-plus"

/**
 * 应用设置
 */
data class AppSettings(
    val currentProviderId: String = ApiProvider.ALIYUN.id,  // 当前选中的服务商
    val providerConfigs: Map<String, ProviderConfig> = emptyMap(),  // 每个服务商的配置
    // ---- 豆糕新增 ----
    val modelProfiles: List<ModelProfile> = emptyList(),   // 已添加的多个模型
    val activeProfileId: String = "",                      // 当前使用的模型
    val thinkingLevel: Int = ThinkingLevel.MEDIUM.level,   // 思考程度（低/中/高）
    // ------------------
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasSeenOnboarding: Boolean = false,
    val maxSteps: Int = 25,
    val cloudCrashReportEnabled: Boolean = true,
    val rootModeEnabled: Boolean = false,
    val suCommandEnabled: Boolean = false
) {
    // 便捷属性：获取当前服务商的配置
    val currentConfig: ProviderConfig
        get() = providerConfigs[currentProviderId] ?: ProviderConfig()

    val currentProvider: ApiProvider
        get() = ApiProvider.ALL.find { it.id == currentProviderId } ?: ApiProvider.ALIYUN

    val apiKey: String get() = currentConfig.apiKey
    val model: String get() = currentConfig.model.ifEmpty { currentProvider.defaultModel }
    val cachedModels: List<String> get() = currentConfig.cachedModels

    val baseUrl: String
        get() = when {
            currentProviderId == "custom" -> currentConfig.customBaseUrl
            // MAI-UI 支持自定义 URL（用于远程部署）
            currentProviderId == "mai_ui" && currentConfig.customBaseUrl.isNotEmpty() -> currentConfig.customBaseUrl
            else -> currentProvider.baseUrl
        }

    // ================= 豆糕新增：运行时实际使用的模型 =================

    /** 当前生效的模型档案；为空时回退到"服务商配置"里的单个模型 */
    val activeProfile: ModelProfile?
        get() = modelProfiles.firstOrNull { it.id == activeProfileId } ?: modelProfiles.firstOrNull()

    val hasProfiles: Boolean get() = modelProfiles.isNotEmpty()

    val activeModelName: String
        get() = activeProfile?.effectiveModel()?.takeIf { it.isNotBlank() } ?: model

    val activeApiKey: String
        get() = activeProfile?.apiKey?.takeIf { it.isNotBlank() } ?: apiKey

    val activeBaseUrl: String
        get() = activeProfile?.effectiveBaseUrl()?.takeIf { it.isNotBlank() } ?: baseUrl

    val activeProvider: ApiProvider
        get() = activeProfile?.provider ?: currentProvider

    val activeProviderId: String
        get() = activeProfile?.providerId ?: currentProviderId

    val activeThinking: ThinkingLevel
        get() = ThinkingLevel.fromLevel(thinkingLevel)
}

/**
 * 设置管理器
 */
class SettingsManager(context: Context) {

    // 普通设置存储
    private val prefs: SharedPreferences =
        context.getSharedPreferences("baozi_settings", Context.MODE_PRIVATE)

    // 加密存储（用于敏感数据如 API Key）
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "baozi_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密失败时回退到普通存储（不应该发生）
            android.util.Log.e("SettingsManager", "Failed to create encrypted prefs", e)
            prefs
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        // 迁移旧的明文 API Key 到加密存储
        migrateApiKeyToSecureStorage()
    }

    /**
     * 迁移旧的明文 API Key 到加密存储
     */
    private fun migrateApiKeyToSecureStorage() {
        val oldApiKey = prefs.getString("api_key", null)
        if (!oldApiKey.isNullOrEmpty()) {
            // 保存到加密存储
            securePrefs.edit().putString("api_key", oldApiKey).apply()
            // 删除旧的明文存储
            prefs.edit().remove("api_key").apply()
            android.util.Log.d("SettingsManager", "API Key migrated to secure storage")
        }
    }

    private fun loadSettings(): AppSettings {
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }

        // 加载当前选中的服务商
        val currentProviderId = prefs.getString("current_provider_id", ApiProvider.ALIYUN.id) ?: ApiProvider.ALIYUN.id

        // 加载每个服务商的配置
        val providerConfigs = mutableMapOf<String, ProviderConfig>()
        for (provider in ApiProvider.ALL) {
            val config = loadProviderConfig(provider.id)
            providerConfigs[provider.id] = config
        }

        // 迁移旧数据（如果有）
        val oldApiKey = securePrefs.getString("api_key", null)
        val oldModel = prefs.getString("model", null)
        val oldBaseUrl = prefs.getString("base_url", null)
        val oldCachedModels = prefs.getStringSet("cached_models", null)

        if (oldApiKey != null || oldModel != null) {
            // 找到旧数据对应的服务商
            val oldProviderId = when (oldBaseUrl) {
                ApiProvider.ALIYUN.baseUrl -> ApiProvider.ALIYUN.id
                ApiProvider.OPENAI.baseUrl -> ApiProvider.OPENAI.id
                ApiProvider.OPENROUTER.baseUrl -> ApiProvider.OPENROUTER.id
                else -> "custom"
            }

            // 迁移到新格式
            val migratedConfig = ProviderConfig(
                apiKey = oldApiKey ?: "",
                model = oldModel ?: "",
                cachedModels = oldCachedModels?.toList() ?: emptyList(),
                customBaseUrl = if (oldProviderId == "custom") oldBaseUrl ?: "" else ""
            )
            providerConfigs[oldProviderId] = migratedConfig
            saveProviderConfig(oldProviderId, migratedConfig)

            // 清除旧数据
            securePrefs.edit().remove("api_key").apply()
            prefs.edit()
                .remove("model")
                .remove("base_url")
                .remove("cached_models")
                .putString("current_provider_id", oldProviderId)
                .apply()

            android.util.Log.d("SettingsManager", "Migrated old settings to provider: $oldProviderId")
        }

        // ---- 豆糕：加载多模型配置 ----
        val modelProfiles = loadModelProfiles(providerConfigs).toMutableList()

        // 首次从旧版本升级：把"服务商里已配置好的那个模型"自动变成一条模型档案
        if (modelProfiles.isEmpty()) {
            val legacyProviderId = providerConfigs.entries
                .firstOrNull { (_, cfg) -> cfg.apiKey.isNotBlank() || cfg.model.isNotBlank() }
                ?.key
            if (legacyProviderId != null) {
                val cfg = providerConfigs.getValue(legacyProviderId)
                val provider = ApiProvider.ALL.find { it.id == legacyProviderId } ?: ApiProvider.ALIYUN
                modelProfiles.add(
                    ModelProfile(
                        id = UUID.randomUUID().toString(),
                        name = ModelProfile.defaultName(provider, cfg.model),
                        providerId = legacyProviderId,
                        model = cfg.model.ifBlank { provider.defaultModel },
                        apiKey = cfg.apiKey,
                        baseUrl = cfg.customBaseUrl
                    )
                )
                saveModelProfiles(modelProfiles)
            }
        }

        val storedActiveId = prefs.getString("active_profile_id", "") ?: ""
        val activeProfileId = if (modelProfiles.any { it.id == storedActiveId }) {
            storedActiveId
        } else {
            modelProfiles.firstOrNull()?.id ?: ""
        }

        return AppSettings(
            currentProviderId = currentProviderId,
            providerConfigs = providerConfigs,
            modelProfiles = modelProfiles.toList(),
            activeProfileId = activeProfileId,
            thinkingLevel = prefs.getInt("thinking_level", ThinkingLevel.MEDIUM.level),
            themeMode = themeMode,
            hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false),
            maxSteps = prefs.getInt("max_steps", 25),
            cloudCrashReportEnabled = prefs.getBoolean("cloud_crash_report_enabled", true),
            rootModeEnabled = prefs.getBoolean("root_mode_enabled", false),
            suCommandEnabled = prefs.getBoolean("su_command_enabled", false)
        )
    }

    // ================== 豆糕新增：多模型配置持久化 ==================

    /**
     * 读取模型列表。API Key 存放在加密存储中，不写进普通配置。
     */
    private fun loadModelProfiles(providerConfigs: Map<String, ProviderConfig>): List<ModelProfile> {
        val raw = prefs.getString("model_profiles", null) ?: return emptyList()
        val result = mutableListOf<ModelProfile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val storedKey = securePrefs.getString("profile_${id}_api_key", "") ?: ""
                // 回退：如果该档案没存过 Key，就沿用其服务商的 Key
                val fallbackKey = providerConfigs[obj.optString("providerId", "")]?.apiKey ?: ""
                ModelProfile.fromJson(obj, storedKey.ifBlank { fallbackKey })?.let { result.add(it) }
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "解析模型列表失败", e)
        }
        return result
    }

    /**
     * 保存模型列表
     */
    private fun saveModelProfiles(profiles: List<ModelProfile>) {
        val arr = JSONArray()
        profiles.forEach { profile ->
            arr.put(profile.toJson())
            securePrefs.edit().putString("profile_${profile.id}_api_key", profile.apiKey).apply()
        }
        prefs.edit().putString("model_profiles", arr.toString()).apply()
    }

    /**
     * 新增一个模型（返回新建的档案）
     */
    fun addModelProfile(
        name: String,
        providerId: String,
        model: String,
        apiKey: String,
        baseUrl: String
    ): ModelProfile {
        val provider = ApiProvider.ALL.find { it.id == providerId } ?: ApiProvider.CUSTOM
        val profile = ModelProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { ModelProfile.defaultName(provider, model) },
            providerId = providerId,
            model = model.ifBlank { provider.defaultModel },
            apiKey = apiKey,
            baseUrl = baseUrl
        )
        val newList = _settings.value.modelProfiles + profile
        saveModelProfiles(newList)
        prefs.edit().putString("active_profile_id", profile.id).apply()
        _settings.value = _settings.value.copy(
            modelProfiles = newList,
            activeProfileId = profile.id
        )
        return profile
    }

    /**
     * 修改一个已有模型
     */
    fun updateModelProfile(profile: ModelProfile) {
        val newList = _settings.value.modelProfiles.map { if (it.id == profile.id) profile else it }
        saveModelProfiles(newList)
        _settings.value = _settings.value.copy(modelProfiles = newList)
    }

    /**
     * 删除一个模型
     */
    fun deleteModelProfile(id: String) {
        val newList = _settings.value.modelProfiles.filterNot { it.id == id }
        securePrefs.edit().remove("profile_${id}_api_key").apply()
        saveModelProfiles(newList)
        val newActive = if (_settings.value.activeProfileId == id) {
            newList.firstOrNull()?.id ?: ""
        } else {
            _settings.value.activeProfileId
        }
        prefs.edit().putString("active_profile_id", newActive).apply()
        _settings.value = _settings.value.copy(modelProfiles = newList, activeProfileId = newActive)
    }

    /**
     * 切换当前使用的模型（输入框处切换）
     */
    fun setActiveProfile(id: String) {
        if (_settings.value.modelProfiles.none { it.id == id }) return
        prefs.edit().putString("active_profile_id", id).apply()
        _settings.value = _settings.value.copy(activeProfileId = id)
    }

    /**
     * 设置思考程度（低 / 中 / 高）
     */
    fun updateThinkingLevel(level: Int) {
        val clamped = level.coerceIn(0, 2)
        prefs.edit().putInt("thinking_level", clamped).apply()
        _settings.value = _settings.value.copy(thinkingLevel = clamped)
    }

    /**
     * 加载指定服务商的配置
     */
    private fun loadProviderConfig(providerId: String): ProviderConfig {
        val prefix = "provider_${providerId}_"
        return ProviderConfig(
            apiKey = securePrefs.getString("${prefix}api_key", "") ?: "",
            model = prefs.getString("${prefix}model", "") ?: "",
            cachedModels = prefs.getStringSet("${prefix}cached_models", emptySet())?.toList() ?: emptyList(),
            customBaseUrl = prefs.getString("${prefix}custom_base_url", "") ?: ""
        )
    }

    /**
     * 保存指定服务商的配置
     */
    private fun saveProviderConfig(providerId: String, config: ProviderConfig) {
        val prefix = "provider_${providerId}_"
        securePrefs.edit().putString("${prefix}api_key", config.apiKey).apply()
        prefs.edit()
            .putString("${prefix}model", config.model)
            .putStringSet("${prefix}cached_models", config.cachedModels.toSet())
            .putString("${prefix}custom_base_url", config.customBaseUrl)
            .apply()
    }

    /**
     * 更新当前服务商的配置
     */
    private fun updateCurrentConfig(update: (ProviderConfig) -> ProviderConfig) {
        val currentId = _settings.value.currentProviderId
        val currentConfig = _settings.value.currentConfig
        val newConfig = update(currentConfig)

        saveProviderConfig(currentId, newConfig)

        val newConfigs = _settings.value.providerConfigs.toMutableMap()
        newConfigs[currentId] = newConfig
        _settings.value = _settings.value.copy(providerConfigs = newConfigs)
    }

    fun updateApiKey(apiKey: String) {
        updateCurrentConfig { it.copy(apiKey = apiKey) }
    }

    fun updateBaseUrl(baseUrl: String) {
        // 自定义服务商和 MAI-UI 可以修改 URL
        val providerId = _settings.value.currentProviderId
        if (providerId == "custom" || providerId == "mai_ui") {
            updateCurrentConfig { it.copy(customBaseUrl = baseUrl) }
        }
    }

    fun updateModel(model: String) {
        updateCurrentConfig { it.copy(model = model) }
    }

    /**
     * 更新缓存的模型列表（从 API 获取后调用）
     */
    fun updateCachedModels(models: List<String>) {
        val distinctModels = models.distinct()
        updateCurrentConfig { it.copy(cachedModels = distinctModels) }
    }

    /**
     * 清空缓存的模型列表
     */
    fun clearCachedModels() {
        updateCurrentConfig { it.copy(cachedModels = emptyList()) }
    }

    /**
     * 选择服务商（切换时自动加载该服务商的配置）
     */
    fun selectProvider(provider: ApiProvider) {
        prefs.edit().putString("current_provider_id", provider.id).apply()
        _settings.value = _settings.value.copy(currentProviderId = provider.id)
    }

    /**
     * 获取当前服务商
     */
    fun getCurrentProvider(): ApiProvider {
        return _settings.value.currentProvider
    }

    /**
     * 判断是否使用自定义 URL
     */
    fun isCustomUrl(): Boolean {
        return _settings.value.currentProviderId == "custom"
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setOnboardingSeen() {
        prefs.edit().putBoolean("has_seen_onboarding", true).apply()
        _settings.value = _settings.value.copy(hasSeenOnboarding = true)
    }

    fun updateMaxSteps(maxSteps: Int) {
        val validSteps = maxSteps.coerceIn(5, 100) // 限制范围 5-100
        prefs.edit().putInt("max_steps", validSteps).apply()
        _settings.value = _settings.value.copy(maxSteps = validSteps)
    }

    fun updateCloudCrashReportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_crash_report_enabled", enabled).apply()
        _settings.value = _settings.value.copy(cloudCrashReportEnabled = enabled)
    }

    fun updateRootModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("root_mode_enabled", enabled).apply()
        _settings.value = _settings.value.copy(rootModeEnabled = enabled)
        // 关闭 Root 模式时，同时关闭 su -c
        if (!enabled) {
            updateSuCommandEnabled(false)
        }
    }

    fun updateSuCommandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("su_command_enabled", enabled).apply()
        _settings.value = _settings.value.copy(suCommandEnabled = enabled)
    }
}

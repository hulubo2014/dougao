package com.dougao.app.vlm

import android.graphics.Bitmap
import android.util.Base64
import com.dougao.app.data.ThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * VLM (Vision Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4V, Qwen-VL, Claude, etc.)
 *
 * 豆糕新增：
 * - [thinkingLevel] 思考程度（低 / 中 / 高），自动映射为对应服务商支持的推理参数；
 *   若服务端不接受这些参数，会**自动降级重试**，保证任何模型都能正常工作。
 */
class VLMClient(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-vision-preview",
    private val thinkingLevel: Int = ThinkingLevel.MEDIUM.level
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val currentLevel: ThinkingLevel = ThinkingLevel.fromLevel(thinkingLevel)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MS = 1000L

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }

        /**
         * 从 API 获取可用模型列表
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("Base URL 不能为空"))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val cleanBaseUrl = normalizeUrl(baseUrl.removeSuffix("/chat/completions"))

            val request = try {
                Request.Builder()
                    .url("$cleanBaseUrl/models")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(Exception("Base URL 格式无效: ${e.message}"))
            }

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val data = json.optJSONArray("data") ?: JSONArray()
                        val models = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i)
                            if (item != null) {
                                val id = item.optString("id", "").trim()
                                if (id.isNotEmpty()) {
                                    models.add(id)
                                }
                            }
                        }
                        Result.success(models)
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ------------------------------------------------------------------
    // 请求体构造（含"思考程度"）
    // ------------------------------------------------------------------

    private fun maxTokensFor(level: ThinkingLevel): Int = when (level) {
        ThinkingLevel.LOW -> 2048
        ThinkingLevel.MEDIUM -> 4096
        ThinkingLevel.HIGH -> 8192
    }

    /**
     * 思考程度 -> 服务商推理参数
     *
     * 只在**明确识别到模型支持**时才附加，避免服务端直接返回 400。
     * 即使判断失误，[callChat] 里还有一次"最小请求体"兜底重试。
     */
    private fun reasoningParams(level: ThinkingLevel): Map<String, Any>? {
        val m = model.lowercase()

        // 通义千问 Qwen3 系列（含 qwen3-vl）
        if (m.contains("qwen3") || m.contains("qwq")) {
            return mapOf(
                "enable_thinking" to (level != ThinkingLevel.LOW),
                "thinking_budget" to when (level) {
                    ThinkingLevel.LOW -> 512
                    ThinkingLevel.MEDIUM -> 2048
                    ThinkingLevel.HIGH -> 8192
                }
            )
        }

        // OpenAI 推理系列 / GPT-5 系列
        if (Regex("(^|[/:._-])(o1|o3|o4|gpt-5)").containsMatchIn(m)) {
            return mapOf(
                "reasoning_effort" to when (level) {
                    ThinkingLevel.LOW -> "low"
                    ThinkingLevel.MEDIUM -> "medium"
                    ThinkingLevel.HIGH -> "high"
                }
            )
        }

        // GLM 系列
        if (m.contains("glm-4.5") || m.contains("glm-4.6") || m.contains("autoglm")) {
            return mapOf(
                "thinking" to mapOf(
                    "type" to if (level == ThinkingLevel.LOW) "disabled" else "enabled"
                )
            )
        }

        return null
    }

    /**
     * @param rich true = 带完整参数；false = 最小可用请求体（兜底）
     */
    private fun buildRequestBody(messagesJson: JSONArray, rich: Boolean): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", messagesJson)

        if (!rich) {
            // 兜底：几乎所有 OpenAI 兼容服务都接受的最小请求体
            body.put("max_tokens", 4096)
            return body
        }

        body.put("max_tokens", maxTokensFor(currentLevel))
        body.put("temperature", 0.0)

        val reasoning = reasoningParams(currentLevel)
        if (reasoning == null) {
            body.put("top_p", 0.85)
            body.put("frequency_penalty", 0.2)
        } else {
            for ((k, v) in reasoning) {
                body.put(k, v)
            }
        }

        return body
    }

    /**
     * 统一 chat/completions 调用，带重试与参数降级
     *
     * 重试策略：
     * - 400 / 422：多半是"思考程度"这类参数服务端不认识，**去掉这些参数**立刻重试
     * - 429：被限流，退避后重试
     * - 5xx：上游网关抖动（如 Upstream gateway error），退避后重试
     * - 401 / 403 / 404：Key 或地址不对，重试没意义，直接返回错误
     */
    private suspend fun callChat(messagesJson: JSONArray): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var rich = true
        var attempt = 0

        while (attempt < MAX_RETRIES) {
            attempt++
            var waitBeforeNext = RETRY_DELAY_MS * attempt

            try {
                val requestBody = buildRequestBody(messagesJson, rich)

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                var shouldReturn = false
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        return@withContext parseContent(responseBody)
                    }

                    lastException = Exception(friendlyError(response.code, responseBody))

                    when {
                        // 参数不支持 -> 降级为最小请求体，马上重试
                        (response.code == 400 || response.code == 422) && rich -> {
                            println("[VLMClient] HTTP ${response.code}，改用最小参数重试")
                            rich = false
                            waitBeforeNext = 0
                        }

                        // 限流：多等一会儿
                        response.code == 429 -> {
                            waitBeforeNext = 2000L * attempt
                            println("[VLMClient] HTTP 429 被限流，${waitBeforeNext}ms 后重试")
                        }

                        // 上游网关抖动：退避重试
                        response.code in 500..599 -> {
                            println("[VLMClient] HTTP ${response.code} 服务端异常，${waitBeforeNext}ms 后重试")
                        }

                        // Key / 地址 / 权限问题，重试无意义
                        else -> shouldReturn = true
                    }
                }
                if (shouldReturn) return@withContext Result.failure(lastException!!)

                if (waitBeforeNext > 0 && attempt < MAX_RETRIES) delay(waitBeforeNext)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
            } catch (e: java.net.SocketTimeoutException) {
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
            } catch (e: java.io.IOException) {
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 把服务端返回的错误整理成一句人话，方便直接显示给用户看
     */
    private fun friendlyError(code: Int, body: String): String {
        val raw = body.trim().take(300)
        val detail = try {
            val json = JSONObject(raw)
            when (val err = json.opt("error")) {
                is JSONObject -> err.optString("message", "").ifBlank { raw }
                is String -> err.ifBlank { raw }
                else -> json.optString("message", "").ifBlank { raw }
            }
        } catch (e: Exception) {
            raw
        }

        val hint = when {
            code == 401 || code == 403 -> "（API Key 不正确或没有权限）"
            code == 404 -> "（接口地址可能填错了，检查 Base URL）"
            code == 429 -> "（请求太频繁，已自动重试）"
            code in 500..599 -> "（模型服务端临时故障，已自动重试）"
            else -> ""
        }
        return "HTTP $code：$detail$hint"
    }

    /**
     * 解析返回内容（兼容 content 为字符串 / 数组两种格式）
     */
    private fun parseContent(responseBody: String): Result<String> {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return Result.failure(Exception("模型没有返回内容"))
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return Result.failure(Exception("返回格式异常"))
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        append(part.optString("text", ""))
                    }
                }
                else -> ""
            }
            if (text.isBlank()) {
                Result.failure(Exception("模型返回了空内容"))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    /**
     * 调用 VLM 进行多模态推理
     */
    suspend fun predict(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): Result<String> {
        val encodedImages = images.map { bitmapToBase64Url(it) }

        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            encodedImages.forEach { imageUrl ->
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", imageUrl)
                    })
                })
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }

        return callChat(messages)
    }

    /**
     * 纯文本推理（豆糕新增：文件工作区不需要截图，更快更省）
     */
    suspend fun predictText(prompt: String): Result<String> {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        return callChat(messages)
    }

    /**
     * 调用 VLM 进行多模态推理 (使用完整对话历史)
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray
    ): Result<String> = callChat(messagesJson)

    /**
     * Bitmap 转 Base64 URL (只压缩质量，不压缩分辨率)
     */
    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[VLMClient] 图片压缩: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}

/**
 * 常用 VLM 配置
 */
object VLMConfigs {
    fun gpt4v(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4-vision-preview"
    )

    fun qwenVL(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-max"
    )

    fun claude(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-20241022"
    )

    fun custom(apiKey: String, baseUrl: String, model: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )
}

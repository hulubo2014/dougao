package com.dougao.app.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dougao.app.data.WorkspaceEntry
import com.dougao.app.files.FileGateway
import com.dougao.app.files.FileNode
import com.dougao.app.vlm.VLMClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件助手 Agent（豆糕新增）
 *
 * 和"看屏幕点来点去"的豆糕模式不同，这个 Agent **完全不操作屏幕**：
 * 它直接读取用户授权目录里的文件内容，直接写回修改结果。
 *
 * 工具协议采用纯文本 JSON，任何 OpenAI 兼容模型都能用，不依赖原生 function calling。
 */
class FileAgent(
    private val context: Context,
    private val gateway: FileGateway,
    private val vlm: VLMClient,
    private val entry: WorkspaceEntry
) {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _state = MutableStateFlow(FileAgentState())
    val state: StateFlow<FileAgentState> = _state

    /** 需要用户确认的操作（目前用于删除文件） */
    private val _pendingConfirm = MutableStateFlow<String?>(null)
    val pendingConfirm: StateFlow<String?> = _pendingConfirm
    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    fun resolveConfirm(approved: Boolean) {
        confirmDeferred?.complete(approved)
        confirmDeferred = null
        _pendingConfirm.value = null
    }

    private suspend fun askConfirm(message: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmDeferred = deferred
        _pendingConfirm.value = message
        return try {
            deferred.await()
        } finally {
            confirmDeferred = null
            _pendingConfirm.value = null
        }
    }

    // ------------------------------------------------------------------

    suspend fun run(instruction: String, maxSteps: Int = 20): AgentResult = withContext(Dispatchers.IO) {
        stopped = false
        _logs.value = emptyList()
        val startedAt = System.currentTimeMillis()
        _state.value = FileAgentState(isRunning = true, instruction = instruction)

        log("开始处理: $instruction")
        log("工作区: ${entry.displayName} (${if (entry.isDirectory) "文件夹" else "单个文件"})")

        // 目录概览，给模型提供上下文
        val overview = if (entry.isDirectory) {
            gateway.treeText(entry, "", maxDepth = 3, maxNodes = 200)
        } else {
            val size = gateway.rootOf(entry)?.length() ?: 0L
            "（当前是一个单独的文件：${entry.displayName}，$size 字节。读取时 path 留空即可）"
        }

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt(overview))
        })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", instruction)
        })

        var finished = false
        var finalMessage = "已完成"
        var step = 0
        var failedTool = ""
        var failedStreak = 0

        while (step < maxSteps && !stopped) {
            step++
            _state.value = _state.value.copy(
                currentStep = step,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            log("\n===== 第 $step 步 =====")

            val response = vlm.predictWithContext(messages)
            if (response.isFailure) {
                val err = response.exceptionOrNull()?.message ?: "未知错误"
                log("❌ 模型调用失败: $err")
                _state.value = _state.value.copy(
                    isRunning = false,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    errorMessage = err
                )
                return@withContext AgentResult(false, "模型调用失败: $err")
            }

            val text = response.getOrThrow()
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", text)
            })

            val call = parseToolCall(text)
            if (call == null) {
                log("⚠️ 无法解析工具调用，提示模型重试")
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        "你的回复里没有找到合法的工具调用 JSON。请严格按格式输出：\n" +
                            "### Thought ###\n一句话说明\n### Tool ###\n" +
                            "{\"tool\":\"read_file\",\"path\":\"文件名\"}"
                    )
                })
                continue
            }

            val tool = call.optString("tool", "")
            val thought = parseThought(text)
            if (thought.isNotBlank()) log("💭 $thought")
            log("🔧 调用工具: $tool")

            if (tool == "finish") {
                finalMessage = call.optString("message", "已完成").ifBlank { "已完成" }
                log("✅ $finalMessage")
                finished = true
                _state.value = _state.value.copy(summary = finalMessage)
                break
            }

            val result = executeTool(tool, call)
            val clipped = if (result.length > 20000) result.substring(0, 20000) + "\n...(已截断)" else result
            log("↳ ${clipResult(result)}")

            val failed = result.startsWith("错误")
            if (failed) {
                if (tool == failedTool) {
                    failedStreak++
                } else {
                    failedTool = tool
                    failedStreak = 1
                }
            } else {
                failedTool = ""
                failedStreak = 0
            }

            _state.value = _state.value.copy(
                steps = _state.value.steps + FileStep(
                    index = step,
                    thought = thought,
                    tool = tool,
                    detail = describeCall(call),
                    result = clipResult(result),
                    failed = failed
                ),
                elapsedMs = System.currentTimeMillis() - startedAt
            )

            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", "### 工具执行结果 ###\n$clipped")
            })

            // 同一个工具连续失败 3 次 = 模型卡死了，明确要求它换一种做法
            if (failedStreak >= 3) {
                log("⚠️「$tool」连续失败 $failedStreak 次，已提示模型换一种做法")
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put(
                        "content",
                        "你已经连续 $failedStreak 次用「$tool」失败，不要再重复同样的调用。请换一种做法：\n" +
                            "1. 先用 list_files 确认目录里到底有哪些文件、名字怎么写；\n" +
                            "2. 如果当前工作区是「单个文件」，所有工具的 path 一律留空；\n" +
                            "3. 如果要清空内容，用 write_file 写入空字符串，不要用 create_file；\n" +
                            "4. 如果确实做不到，直接调用 finish 并说明原因。"
                    )
                })
                failedStreak = 0
                failedTool = ""
            }

            // 控制上下文长度
            trimMessages(messages)

            delay(120)
        }

        _state.value = _state.value.copy(
            isRunning = false,
            isCompleted = finished,
            elapsedMs = System.currentTimeMillis() - startedAt,
            summary = if (finished) finalMessage else _state.value.summary
        )
        if (!finished && !stopped) {
            log("⚠️ 达到最大步数限制（$maxSteps）")
            return@withContext AgentResult(false, "达到最大步数限制，任务可能未完成")
        }
        if (stopped) {
            log("用户已停止")
            return@withContext AgentResult(false, "已停止")
        }
        AgentResult(true, finalMessage)
    }

    /** 从模型回复里取出 ### Thought ### 那一段，给界面展示 */
    private fun parseThought(text: String): String {
        val marker = "### Thought ###"
        val idx = text.indexOf(marker)
        val raw = if (idx >= 0) {
            text.substring(idx + marker.length)
                .substringBefore("### Tool ###")
                .substringBefore("###")
        } else {
            ""
        }
        return raw.replace("###", "").trim().take(300)
    }

    /** 把工具调用参数整理成一行摘要，展开面板里显示用 */
    private fun describeCall(call: JSONObject): String {
        val tool = call.optString("tool", "")
        val path = call.optString("path", "")
        val pathLabel = path.ifBlank { if (entry.isDirectory) "根目录" else entry.displayName }
        return when (tool) {
            "read_file" -> pathLabel
            "list_files" -> pathLabel
            "write_file" -> "$pathLabel · 写入 ${call.optString("content", "").length} 字"
            "append_file" -> "$pathLabel · 追加 ${call.optString("content", "").length} 字"
            "edit_file" -> "$pathLabel · 替换 ${call.optString("old", "").length} 字的片段"
            "create_file" -> "$pathLabel · ${call.optString("content", "").length} 字"
            "delete_file" -> pathLabel
            "rename_file" -> "$pathLabel → ${call.optString("new_name", "")}"
            "search_files" -> "关键词：${call.optString("keyword", "")}"
            else -> pathLabel
        }
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    private fun buildSystemPrompt(overview: String): String = buildString {
        append("你是「豆糕」的文件助手。你可以直接读取和修改用户授权目录中的文件，")
        append("**不需要也不允许操作手机屏幕**，直接操作文件内容即可。\n\n")

        append("### 工作区 ###\n")
        append("名称: ${entry.displayName}\n")
        append("类型: ${if (entry.isDirectory) "文件夹" else "单个文件"}\n")
        if (!entry.isDirectory) {
            append("⚠️ 注意：当前工作区是**一个单独的文件**，下面所有工具的 path 参数请一律**留空**，")
            append("操作的就是这个文件本身。\n")
            append("如果用户说\"把内容删掉/清空\"，用 write_file 写一个空字符串即可。\n")
        }
        if (useOriginalFile) {
            append("当前打开的文件: $originalRelativePath\n")
        }
        append("目录概览:\n$overview\n\n")

        append("### 可用工具 ###\n")
        append("所有 path 都是相对工作区根目录的路径，例如 \"src/main.txt\"（单个文件工作区留空即可）。\n")
        append("1. list_files  {\"tool\":\"list_files\",\"path\":\"\"}  —— 列出目录内容，path 为空表示根目录\n")
        append("2. read_file   {\"tool\":\"read_file\",\"path\":\"a.txt\"}  —— 读取文件全文\n")
        append("3. write_file  {\"tool\":\"write_file\",\"path\":\"a.txt\",\"content\":\"新的完整内容\"}  —— 覆盖写入整个文件\n")
        append("4. edit_file   {\"tool\":\"edit_file\",\"path\":\"a.txt\",\"old\":\"要替换的原文\",\"new\":\"替换后的内容\"}  —— 精确替换（推荐，改动最小）\n")
        append("5. create_file {\"tool\":\"create_file\",\"path\":\"new.txt\",\"content\":\"内容\"}  —— 新建文件\n")
        append("6. append_file {\"tool\":\"append_file\",\"path\":\"a.txt\",\"content\":\"追加的内容\"}  —— 在文件末尾追加\n")
        append("7. delete_file {\"tool\":\"delete_file\",\"path\":\"a.txt\"}  —— 删除文件（会先请用户确认）\n")
        append("8. rename_file {\"tool\":\"rename_file\",\"path\":\"a.txt\",\"new_name\":\"b.txt\"}  —— 重命名\n")
        append("9. search_files {\"tool\":\"search_files\",\"keyword\":\"关键词\"}  —— 按文件名/内容搜索\n")
        append("10. finish     {\"tool\":\"finish\",\"message\":\"一句话说明你做了什么\"}  —— 任务完成时调用\n\n")

        append("### 重要规则 ###\n")
        append("1. 修改已有文件前，**必须先 read_file 读取原文**，否则 old 文本对不上会导致替换失败。\n")
        append("2. 小改动优先用 edit_file，整体重写才用 write_file。\n")
        append("3. 一次只调用一个工具，拿到结果后再决定下一步。\n")
        append("4. 路径不能包含 .. ，也不能是绝对路径。\n")
        append("5. 想把文件内容清空，用 write_file 写空字符串，不要用 create_file 去「重建」文件。\n")
        append("6. 同一个调用失败两次就换思路，不要连续重复。\n")
        append("7. 全部完成后必须调用 finish 并说明改了什么。\n\n")

        append("### 输出格式（必须严格遵守）###\n")
        append("### Thought ###\n")
        append("一句话说明你的判断\n")
        append("### Tool ###\n")
        append("{\"tool\":\"read_file\",\"path\":\"a.txt\"}\n")
    }

    // ------------------------------------------------------------------
    // 工具执行
    // ------------------------------------------------------------------

    private fun safePath(raw: String): String? {
        val p = raw.trim().replace('\\', '/')
        if (p.contains("..")) return null
        if (p.startsWith("/") || p.contains("://")) return null
        return p.trim('/')
    }

    /**
     * 统一处理 path 参数。
     *
     * 当工作区是「单个文件」时，模型给的任何 path 都归一到目标文件本身（空串 = 工作区根）。
     * 之前没做这一步，导致单文件工作区里 write_file / create_file 全部报
     * "无法创建/定位文件"，AI 卡在同一个错误上反复重试。
     */
    private fun normalizePath(raw: String): String? {
        if (!entry.isDirectory) return ""
        return safePath(raw)
    }

    /** 定位一个「已经存在」的目标：单文件工作区就是它自己 */
    private fun resolveExisting(safe: String): DocumentFile? {
        if (!entry.isDirectory) return gateway.rootOf(entry)
        return gateway.resolve(entry, safe)
    }

    /** 定位一个「要写入」的目标：单文件工作区就是它自己；文件夹工作区会自动建父目录 */
    private fun resolveWritable(safe: String): DocumentFile? {
        if (!entry.isDirectory) return gateway.rootOf(entry)
        if (safe.isEmpty()) return null
        return gateway.ensurePath(entry, safe)
    }

    /** 错误信息 / 界面里显示的目标名字 */
    private fun displayTarget(safe: String): String =
        if (!entry.isDirectory) entry.displayName else safe

    private suspend fun executeTool(tool: String, call: JSONObject): String {
        return try {
            when (tool) {
                "list_files" -> toolListFiles(call.optString("path", ""))
                "read_file" -> toolReadFile(call.optString("path", ""))
                "write_file" -> toolWriteFile(call.optString("path", ""), call.optString("content", ""))
                "edit_file" -> toolEditFile(
                    call.optString("path", ""),
                    call.optString("old", ""),
                    call.optString("new", "")
                )
                "create_file" -> toolCreateFile(call.optString("path", ""), call.optString("content", ""))
                "append_file" -> toolAppendFile(call.optString("path", ""), call.optString("content", ""))
                "delete_file" -> toolDeleteFile(call.optString("path", ""))
                "rename_file" -> toolRenameFile(call.optString("path", ""), call.optString("new_name", ""))
                "search_files" -> toolSearchFiles(call.optString("keyword", ""), call.optString("path", ""))
                else -> "错误：未知工具 $tool"
            }
        } catch (e: Exception) {
            "错误：${e.message}"
        }
    }

    private fun toolListFiles(path: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val nodes = gateway.list(entry, safe)
        if (nodes.isEmpty()) return "（空目录）"
        return nodes.joinToString("\n") { n ->
            if (n.isDirectory) "[目录] ${n.relativePath}/" else "[文件] ${n.relativePath}  (${n.size}字节)"
        }
    }

    private fun toolReadFile(path: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val target = resolveExisting(safe)
            ?: return "错误：文件不存在 —— ${displayTarget(safe)}"
        if (target.isDirectory) return "错误：${displayTarget(safe)} 是目录，请用 list_files"

        val name = target.name ?: displayTarget(safe)
        val uri = target.uri
        val ext = FileNode.extensionOf(name)

        // 二进制/文档类文件走专用解析
        if (ext in setOf("docx", "xlsx", "pptx")) {
            val text = gateway.readDocumentText(uri, name)
            return text?.takeIf { it.isNotBlank() }
                ?: "（这是 $ext 文档，暂时无法提取文字内容）"
        }
        if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "mp3", "mp4", "zip", "rar", "7z", "apk", "pdf")) {
            return "（$name 是二进制文件，内容无法以文本方式读取）"
        }

        val text = gateway.readText(uri)
        recordFileRead(uri, safe, text)
        return "文件: $name（共 ${text.length} 字）\n----\n$text"
    }

    private fun toolWriteFile(path: String, content: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val file = resolveWritable(safe)
            ?: return "错误：无法创建/定位文件 —— ${displayTarget(safe)}"
        return if (gateway.writeText(file.uri, content)) {
            recordChange("覆盖写入", displayTarget(safe))
            "写入成功：${displayTarget(safe)}（${content.length} 字）"
        } else {
            "错误：写入失败，可能没有该目录的写权限"
        }
    }

    private fun toolEditFile(path: String, old: String, new: String): String {
        if (old.isEmpty()) return "错误：old 不能为空"
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val label = displayTarget(safe)
        val file = resolveExisting(safe) ?: return "错误：文件不存在 —— $label"

        val current = gateway.readText(file.uri, maxChars = Int.MAX_VALUE)
        if (current.startsWith("(") && current.endsWith(")")) {
            return "错误：无法完整读取 $label（$current），已放弃编辑"
        }
        val index = current.indexOf(old)
        if (index < 0) {
            return "错误：在 $label 中没有找到要替换的原文。请先 read_file 确认原文（注意空格和换行要完全一致）。"
        }
        val occurrences = Regex(Regex.escape(old)).findAll(current).count()
        val updated = current.replaceFirst(old, new)
        return if (gateway.writeText(file.uri, updated)) {
            recordChange("编辑", label)
            "编辑成功：$label（替换了 ${if (occurrences > 1) "第 1 处，共 $occurrences 处匹配" else "1 处"}）"
        } else {
            "错误：写回失败"
        }
    }

    private fun toolCreateFile(path: String, content: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        // 单文件工作区没有"新建"的概念，直接写入当前这个文件
        if (!entry.isDirectory) {
            return toolWriteFile("", content)
        }
        if (safe.isEmpty()) return "错误：请提供文件名，例如 note.txt"
        if (gateway.exists(entry, safe)) return "错误：$safe 已存在，请用 write_file 或 edit_file"
        return toolWriteFile(safe, content)
    }

    private fun toolAppendFile(path: String, content: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val label = displayTarget(safe)
        val file = resolveExisting(safe) ?: return "错误：文件不存在 —— $label"
        val current = gateway.readText(file.uri, maxChars = Int.MAX_VALUE)
        if (current.startsWith("(") && current.endsWith(")")) {
            return "错误：无法完整读取 $label（$current），已放弃追加"
        }
        return if (gateway.writeText(file.uri, current + content)) {
            recordChange("追加", label)
            "追加成功：$label"
        } else {
            "错误：写入失败"
        }
    }

    private suspend fun toolDeleteFile(path: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val label = displayTarget(safe)
        val file = resolveExisting(safe) ?: return "错误：文件不存在 —— $label"
        val approved = askConfirm("AI 想要删除：$label\n确定允许吗？")
        if (!approved) return "用户拒绝了删除操作"
        return if (gateway.delete(file)) {
            recordChange("删除", label)
            "已删除：$label"
        } else {
            "错误：删除失败"
        }
    }

    private fun toolRenameFile(path: String, newName: String): String {
        val safe = normalizePath(path) ?: return "错误：非法路径"
        val label = displayTarget(safe)
        if (newName.isBlank() || newName.contains('/') || newName.contains("..")) return "错误：新名称不合法"
        val file = resolveExisting(safe) ?: return "错误：文件不存在 —— $label"
        return if (gateway.rename(file, newName)) {
            recordChange("重命名", label)
            "重命名成功：$label -> $newName"
        } else {
            "错误：重命名失败"
        }
    }

    private fun toolSearchFiles(keyword: String, path: String): String {
        if (keyword.isBlank()) return "错误：keyword 不能为空"
        val safe = safePath(path) ?: return "错误：非法路径"
        val hits = mutableListOf<String>()
        var scanned = 0

        fun walk(dirPath: String, depth: Int) {
            if (depth > 5 || hits.size >= 40 || scanned >= 800) return
            val nodes = gateway.list(entry, dirPath)
            for (node in nodes) {
                if (hits.size >= 40 || scanned >= 800) return
                scanned++
                if (node.isDirectory) {
                    if (node.name.startsWith(".")) continue
                    walk(node.relativePath, depth + 1)
                } else {
                    if (node.name.contains(keyword, ignoreCase = true)) {
                        hits.add("[文件名匹配] ${node.relativePath}")
                        continue
                    }
                    if (node.isTextLike && node.size < 512 * 1024) {
                        val content = gateway.readText(node.uri, maxChars = 200_000)
                        if (content.contains(keyword, ignoreCase = true)) {
                            val line = content.lineSequence()
                                .indexOfFirst { it.contains(keyword, ignoreCase = true) } + 1
                            hits.add("[内容匹配] ${node.relativePath} 第 $line 行")
                        }
                    }
                }
            }
        }

        walk(safe, 0)
        return if (hits.isEmpty()) "没有找到包含「$keyword」的文件" else hits.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // 解析 & 辅助
    // ------------------------------------------------------------------

    /**
     * 从模型回复里解析出工具调用 JSON
     */
    private fun parseToolCall(text: String): JSONObject? {
        val marker = "### Tool ###"
        val idx = text.indexOf(marker)
        val start = if (idx >= 0) idx + marker.length else 0

        // 从 start 之后找第一个 '{'，做括号配对
        var i = text.indexOf('{', start)
        if (i < 0) i = text.indexOf('{')
        while (i >= 0) {
            val end = matchBrace(text, i)
            if (end > i) {
                val candidate = text.substring(i, end + 1)
                try {
                    val obj = JSONObject(candidate)
                    if (obj.has("tool")) return obj
                } catch (_: Exception) {
                    // 继续找下一个
                }
                i = text.indexOf('{', i + 1)
            } else {
                break
            }
        }
        return null
    }

    /**
     * 返回与起始 '{' 配对的 '}' 下标，考虑字符串与转义
     */
    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** 控制对话长度，保留 system + 最近若干轮 */
    private fun trimMessages(messages: JSONArray) {
        val maxMessages = 24
        if (messages.length() <= maxMessages) return
        val kept = JSONArray()
        kept.put(messages.getJSONObject(0)) // system
        val startIdx = messages.length() - (maxMessages - 1)
        for (i in startIdx until messages.length()) {
            kept.put(messages.getJSONObject(i))
        }
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        for (i in 0 until kept.length()) messages.put(kept.getJSONObject(i))
    }

    private fun clipResult(result: String): String =
        if (result.length > 200) result.substring(0, 200) + "..." else result

    private fun recordFileRead(uri: Uri, path: String, content: String) {
        lastReadPath = path
        lastReadUri = uri
    }

    private fun recordChange(kind: String, path: String) {
        _state.value = _state.value.copy(changes = _state.value.changes + "$kind: $path")
    }

    private fun log(message: String) {
        println("[豆糕-文件] $message")
        _logs.value = _logs.value + message
    }

    // 供外部设定：当工作区是"单个文件"时，说明当前文件
    var originalRelativePath: String = ""
    var useOriginalFile: Boolean = false

    private var lastReadPath: String = ""
    private var lastReadUri: Uri? = null
}

data class FileAgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val changes: List<String> = emptyList(),
    /** 每一步的"思考 + 文件操作"，供界面折叠面板展示 */
    val steps: List<FileStep> = emptyList(),
    /** 任务完成后的总结 */
    val summary: String? = null,
    /** 已耗时（毫秒） */
    val elapsedMs: Long = 0,
    /** 出错信息 */
    val errorMessage: String? = null
)

/**
 * 文件助手的一步操作。
 * 界面上按 DeepSeek「深度思考」那种折叠面板展示：思考过程 + 调用了什么工具 + 结果。
 */
data class FileStep(
    val index: Int,
    val thought: String,
    val tool: String,
    val detail: String,
    val result: String,
    val failed: Boolean
)

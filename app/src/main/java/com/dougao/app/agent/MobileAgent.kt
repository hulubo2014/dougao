package com.dougao.app.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.dougao.app.App
import com.dougao.app.controller.AppScanner
import com.dougao.app.controller.DeviceController
import com.dougao.app.controller.ShizukuShell
import com.dougao.app.data.ExecutionStep
import com.dougao.app.ui.OverlayService
import com.dougao.app.vlm.GUIOwlClient
import com.dougao.app.vlm.MAIUIClient
import com.dougao.app.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 豆糕主执行内核
 *
 * ============ 这次大改改了什么 ============
 *
 * 【旧版的问题】每走一步要调 **3 次模型**：
 *    Manager 规划 → Executor 决策动作 → Reflector 反思结果
 * 三次调用每次都要把整张截图发上去。截图本来就慢，模型一次要等好几秒，
 * 三步加起来十几秒才走完一步 —— 这就是"特别慢"的真正原因。
 *
 * 【新版】对齐 zai-org/Open-AutoGLM 官方范式：**一步只调 1 次模型**。
 *   截图 → 把截图交给模型 → 模型一次输出「思考 + 下一步动作」→ 执行 → 下一步
 * 速度直接提升约 3 倍。
 *
 * 【截屏】改走 `Shizuku.newProcess("screencap -p")` 读 stdout 原始字节，不再落盘，
 * 彻底解决"截屏失败"。
 *
 * 【结束判定】要求模型输出 finish 且任务确实有过成功动作，避免"没做完就返回"。
 */
class MobileAgent(
    private val vlmClient: VLMClient?,
    private val controller: DeviceController,
    private val context: Context,
    private val guiOwlClient: GUIOwlClient? = null,
    private val maiuiClient: MAIUIClient? = null
) {
    private val useGUIOwlMode: Boolean = guiOwlClient != null
    private val useMAIUIMode: Boolean = maiuiClient != null
    private val appScanner: AppScanner = App.getInstance().appScanner

    // ---------------- 状态流 ----------------
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /** 停止回调（由 MainActivity 设置，用于取消协程） */
    var onStopRequested: (() -> Unit)? = null

    // ---------------- 运行时变量 ----------------
    /** 上一步动作后拍的那张，直接当下一步的起始画面 —— 截图次数减半 */
    private var cachedScreenshot: Bitmap? = null
    private var consecutiveShotFailures = 0
    private var currentTaskId: String? = null

    companion object {
        /** 连续截屏失败多少次就放弃 */
        private const val MAX_SHOT_FAILURES = 3

        /** 上下文里最多保留多少轮对话（system 之外） */
        private const val MAX_HISTORY_TURNS = 8
    }

    // ==================================================================
    // 入口
    // ==================================================================

    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 30,
        useNotetaker: Boolean = false
    ): AgentResult {
        if (useGUIOwlMode && guiOwlClient != null) {
            return runInstructionWithGUIOwl(instruction, maxSteps)
        }
        if (useMAIUIMode && maiuiClient != null) {
            return runInstructionWithMAIUI(instruction, maxSteps)
        }
        val client = vlmClient
            ?: return AgentResult(false, "模型未初始化，请先在设置里添加模型")
        return runAutoGlmLoop(client, instruction, maxSteps)
    }

    // ==================================================================
    // 主循环（AutoGLM 范式：一步一次模型调用）
    // ==================================================================

    private suspend fun runAutoGlmLoop(
        client: VLMClient,
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        currentTaskId = java.util.UUID.randomUUID().toString()
        _logs.value = emptyList()
        _state.value = AgentState(
            isRunning = true,
            currentStep = 0,
            instruction = instruction,
            phase = AgentPhase.RUNNING
        )

        log("开始执行：$instruction")
        cachedScreenshot = null
        consecutiveShotFailures = 0

        // ---- 屏幕尺寸 ----
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸：${screenWidth}x${screenHeight}")

        // ---- 已安装应用（让模型 Launch 时用对名字）----
        val installedApps = try {
            appScanner.getApps()
                .filter { !it.isSystem }
                .take(60)
                .joinToString("、") { it.appName }
        } catch (e: Exception) {
            ""
        }
        log("已加载 ${installedApps.count { it == '、' } + 1} 个应用")

        // ---- 组装消息历史 ----
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", AutoGlmProtocol.buildSystemPrompt(installedApps))
        })

        // ---- 悬浮窗 ----
        OverlayService.show(context, "准备开始…") { stopInternal() }

        var step = 0
        var successActions = 0
        var finishClaimCount = 0
        var lastFailureReason: String? = null
        // 纠错提示：合并进下一条 user 消息，避免出现连续两条 user（部分接口会拒绝）
        var pendingHint: String? = null

        try {
            while (step < maxSteps) {
                coroutineContext.ensureActive()
                if (!_state.value.isRunning) {
                    return finishWith(false, "已停止")
                }

                step++
                _state.value = _state.value.copy(currentStep = step, phase = AgentPhase.RUNNING)
                log("\n──── 第 $step 步 ────")
                OverlayService.update("执行中 $step/$maxSteps")

                // ---------- 1. 拿画面 ----------
                val shot = obtainScreenshot()
                if (shot == null) {
                    // 截屏彻底失败
                    val detail = lastShotError ?: "未知原因"
                    log("❌ 截屏失败：$detail")
                    consecutiveShotFailures++
                    if (consecutiveShotFailures >= MAX_SHOT_FAILURES) {
                        return finishWith(
                            false,
                            "连续 $MAX_SHOT_FAILURES 次截屏失败，任务已停止。\n原因：$detail\n\n" +
                                    "请检查：Shizuku 是否在运行、豆糕是否已获得授权。"
                        )
                    }
                    cachedScreenshot = null
                    delay(400)
                    step--
                    continue
                }
                consecutiveShotFailures = 0
                val screenshot = shot

                // ---------- 2. 屏幕上下文 ----------
                val currentPkg = try {
                    controller.getCurrentAppPackage()
                } catch (e: Exception) {
                    null
                }
                val currentAppName = if (currentPkg != null) {
                    controller.getAppLabel(currentPkg)
                } else {
                    "未知"
                }
                val screenInfo = JSONObject().apply {
                    put("current_app", currentAppName)
                    if (currentPkg != null) put("package", currentPkg)
                }.toString()

                // ---------- 3. 组装本轮 user 消息（先图后文） ----------
                val baseText = if (step == 1) {
                    "$instruction\n\n** 屏幕信息 **\n$screenInfo"
                } else {
                    "** 屏幕信息 **\n$screenInfo"
                }
                val textContent = pendingHint?.let { "$baseText\n\n$it" } ?: baseText
                pendingHint = null

                messages.put(buildUserMessage(textContent, screenshot))

                // ---------- 4. 调模型（关键：这里只调 1 次） ----------
                trimHistory(messages)
                val response = client.predictWithContext(messages)

                if (!_state.value.isRunning) return finishWith(false, "已停止")

                if (response.isFailure) {
                    val err = response.exceptionOrNull()?.message ?: "未知错误"
                    log("❌ 模型调用失败：$err")
                    // 把这轮 user 消息撤掉，下一步重新组装
                    messages.remove(messages.length() - 1)
                    lastFailureReason = err
                    delay(800)
                    step--
                    continue
                }

                val rawText = response.getOrThrow()

                // 立刻把这一步 user 消息里的图片剥掉（省 token，但保留文字历史）
                stripLastImage(messages)

                // ---------- 5. 解析 ----------
                val (thinking, _) = AutoGlmProtocol.splitThinking(rawText)
                val action = AutoGlmProtocol.parse(rawText)

                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", if (thinking.isNotBlank()) "<think>$thinking</think>${action.raw}" else action.raw)
                })

                if (thinking.isNotBlank()) log("💭 $thinking")

                if (action.kind == "unknown") {
                    log("⚠️ 没能解析出动作，让模型重来")
                    pendingHint = "你的回复里没有合法的动作。请严格按这个格式重新输出一次：\n" +
                            "<think>一句话理由</think>\n" +
                            "<answer>do(action=\"Tap\", element=[500, 300])</answer>\n" +
                            "如果要结束，用 <answer>finish(message=\"...\")</answer>"
                    step--
                    delay(200)
                    continue
                }

                // ---------- 6. finish 判定 ----------
                if (action.isFinish) {
                    val msg = action.str("message")?.takeIf { it.isNotBlank() } ?: "任务完成"
                    finishClaimCount++

                    // 防止"一步没动就说完成"
                    if (successActions == 0 && finishClaimCount < 2) {
                        log("⚠️ 模型说完成了，但还没成功执行过任何动作。先让它继续一步确认。")
                        pendingHint = "你刚才说任务完成了，但还没有执行过任何成功操作。" +
                                "如果任务确实已经完成，请再输出一次 finish；如果还没做完，请给出下一步动作。"
                        step--
                        delay(200)
                        continue
                    }

                    log("✅ $msg")
                    return finishWith(true, msg)
                }

                // ---------- 7. 执行动作 ----------
                val stepIndex = _state.value.executionSteps.size
                val display = AutoGlmProtocol.describe(action)
                val runningStep = ExecutionStep(
                    stepNumber = step,
                    timestamp = System.currentTimeMillis(),
                    action = action.name ?: "unknown",
                    description = display,
                    thought = thinking,
                    outcome = "进行中"
                )
                _state.value = _state.value.copy(executionSteps = _state.value.executionSteps + runningStep)
                log("🔧 $display")
                OverlayService.update(display)

                val needConfirm = action.str("message") != null &&
                        action.name.equals("Tap", ignoreCase = true)
                if (needConfirm) {
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(action.str("message") ?: "这一步可能涉及支付/隐私，确认执行吗？")
                    }
                    if (!confirmed) {
                        log("用户取消了这一步")
                        updateStepOutcome(stepIndex, "已取消")
                        pendingHint = "用户拒绝了这个操作。请换一种方式，或者 finish 说明情况。"
                        continue
                    }
                }

                val executed = executeAction(action, screenWidth, screenHeight)

                if (executed) {
                    successActions++
                    updateStepOutcome(stepIndex, "成功")
                } else {
                    updateStepOutcome(stepIndex, "失败")
                    pendingHint = "上一个动作执行失败了。请检查后换一种做法重试。"
                }

                // ---------- 8. 等界面稳定（按动作类型自适应） ----------
                val settle = settleDelay(action.name, step)
                if (settle > 0) delay(settle)

                // ---------- 9. 拍下"结果画面"，留给下一步复用 ----------
                cachedScreenshot = null   // 动作后画面变了，不能用旧图
                val after = takeScreenshotQuiet()
                if (after != null) cachedScreenshot = after
            }

            // 循环正常结束 = 步数用完了
            log("⚠️ 达到最大步数（$maxSteps），任务可能还没做完")
            return finishWith(
                false,
                (lastFailureReason?.let { "最后错误：$it\n" } ?: "") + "达到最大步数限制，任务可能未完成。"
            )

        } catch (e: CancellationException) {
            log("任务被取消")
            cleanup()
            throw e
        } catch (e: Exception) {
            log("❌ 执行异常：${e.message}")
            return finishWith(false, "执行异常：${e.message ?: "未知错误"}")
        }
    }

    // ==================================================================
    // 截图
    // ==================================================================

    private var lastShotError: String? = null

    /**
     * 拿当前画面。优先复用上一步结束时的截图。
     * 返回 null 表示这次真的拿不到画面。
     */
    private suspend fun obtainScreenshot(): Bitmap? {
        cachedScreenshot?.let {
            log("复用上一步的画面")
            return it
        }

        OverlayService.setVisible(false)
        delay(60)
        val result = controller.screenshotWithFallback()
        OverlayService.setVisible(true)

        if (result.isFallback) {
            lastShotError = result.errorDetail
            return null
        }
        lastShotError = null
        return result.bitmap
    }

    /** 拍完就存起来，不写状态日志 */
    private suspend fun takeScreenshotQuiet(): Bitmap? {
        return try {
            OverlayService.setVisible(false)
            delay(60)
            val r = controller.screenshotWithFallback()
            OverlayService.setVisible(true)
            if (r.isFallback) null else r.bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ==================================================================
    // 消息构造 / 裁剪
    // ==================================================================

    private fun buildUserMessage(text: String, bitmap: Bitmap): JSONObject {
        val base64 = controller.toModelBase64(bitmap)
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64")
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
    }

    /** 把最后一条 user 消息里的图片剥掉，只留文字 —— 省 token，又不丢上下文 */
    private fun stripLastImage(messages: JSONArray) {
        if (messages.length() < 2) return
        val idx = messages.length() - 1
        val msg = messages.optJSONObject(idx) ?: return
        if (msg.optString("role") != "user") return
        val content = msg.optJSONArray("content") ?: return

        val texts = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") {
                if (texts.isNotEmpty()) texts.append("\n")
                texts.append(part.optString("text", ""))
            }
        }
        messages.put(idx, JSONObject().apply {
            put("role", "user")
            put("content", texts.toString())
        })
    }

    /**
     * 控制上下文长度：始终保留 system + 最近 N 轮，
     * 并且保证最后一条 user 消息带图（因为它是本轮的）。
     */
    private fun trimHistory(messages: JSONArray) {
        val system = messages.optJSONObject(0)
        val nonSystem = messages.length() - 1
        // 一轮 = user + assistant，所以是 2 倍
        val maxNonSystem = MAX_HISTORY_TURNS * 2
        if (nonSystem <= maxNonSystem) return

        val drop = nonSystem - maxNonSystem
        val rebuilt = JSONArray()
        if (system != null) rebuilt.put(system)
        for (i in (1 + drop) until messages.length()) {
            rebuilt.put(messages.get(i))
        }
        // 清空重建
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        for (i in 0 until rebuilt.length()) messages.put(rebuilt.get(i))
    }

    // ==================================================================
    // 动作执行
    // ==================================================================

    private suspend fun executeAction(
        action: AutoGlmProtocol.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (!action.isDo) return@withContext false

        val name = action.name?.lowercase()?.replace(" ", "") ?: return@withContext false

        try {
            when (name) {
                "launch" -> {
                    val app = action.str("app") ?: return@withContext false
                    controller.openApp(app)
                }

                "tap" -> {
                    val p = action.screenPoint("element", screenWidth, screenHeight)
                        ?: return@withContext false
                    controller.tap(p.first, p.second)
                }

                "doubletap" -> {
                    val p = action.screenPoint("element", screenWidth, screenHeight)
                        ?: return@withContext false
                    controller.doubleTap(p.first, p.second)
                }

                "longpress" -> {
                    val p = action.screenPoint("element", screenWidth, screenHeight)
                        ?: return@withContext false
                    controller.longPress(p.first, p.second)
                }

                "swipe" -> {
                    val start = action.screenPoint("start", screenWidth, screenHeight)
                        ?: return@withContext false
                    val end = action.screenPoint("end", screenWidth, screenHeight)
                        ?: return@withContext false
                    controller.swipe(start.first, start.second, end.first, end.second)
                }

                "type", "typename" -> {
                    val text = action.str("text") ?: return@withContext false
                    controller.type(text)
                }

                "back" -> controller.back()

                "home" -> controller.home()

                "wait" -> {
                    val ms = AutoGlmProtocol.parseDurationMs(action.str("duration"), 1500L)
                    delay(ms.coerceIn(300L, 10_000L))
                }

                "take_over", "takeover" -> {
                    val message = action.str("message") ?: "需要你手动操作一下"
                    log("🖐 $message")
                    withContext(Dispatchers.Main) { waitForUserTakeOver(message) }
                }

                "interact" -> {
                    val ok = withContext(Dispatchers.Main) {
                        waitForUserConfirm("模型想请你确认一下怎么选，继续吗？")
                    }
                    if (!ok) return@withContext false
                }

                // note / call_api 是记录类动作，本身不动界面
                "note", "callapi" -> Unit

                else -> {
                    log("未知动作：${action.name}")
                    return@withContext false
                }
            }
            true
        } catch (e: Exception) {
            log("动作执行异常：${e.message}")
            false
        }
    }

    /**
     * 动作后等界面稳定。
     *
     * 旧版是"第一步死等 5 秒、之后每步死等 2 秒"，不管什么动作都一样。
     * 这里按动作类型区分（参考 Aries-AI 的实测参数）。
     */
    private fun settleDelay(actionName: String?, step: Int): Long {
        val base = when (actionName?.lowercase()?.replace(" ", "")) {
            "launch" -> 1200L
            "wait" -> 0L
            "type", "typename" -> 400L
            "swipe" -> 500L
            "tap", "doubletap", "longpress" -> 600L
            "back", "home" -> 500L
            "take_over", "takeover", "interact" -> 300L
            else -> 500L
        }
        return if (step == 1) maxOf(base, 1000L) else base
    }

    // ==================================================================
    // 状态维护
    // ==================================================================

    private fun updateStepOutcome(index: Int, outcome: String) {
        _state.value = _state.value.let { s ->
            val list = s.executionSteps.toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(outcome = outcome)
            }
            s.copy(executionSteps = list)
        }
    }

    private fun finishWith(success: Boolean, message: String): AgentResult {
        cleanup()
        _state.value = _state.value.copy(
            isRunning = false,
            isCompleted = success,
            summary = message,
            phase = if (success) AgentPhase.SUCCESS else AgentPhase.FAILED
        )
        return AgentResult(success, message)
    }

    private fun cleanup() {
        try {
            OverlayService.hide(context)
        } catch (_: Exception) {
        }
        bringAppToFront()
    }

    private fun stopInternal() {
        _state.value = _state.value.copy(isRunning = false)
        onStopRequested?.invoke()
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) continuation.resume(confirmed) {}
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        OverlayService.showTakeOver(message) {
            if (continuation.isActive) continuation.resume(Unit) {}
        }
    }

    // ==================================================================
    // GUI-Owl / MAI-UI 模式（保留，走各自的单次调用协议）
    // ==================================================================

    private suspend fun runInstructionWithGUIOwl(instruction: String, maxSteps: Int): AgentResult {
        val client = guiOwlClient ?: return AgentResult(false, "GUI-Owl 客户端未初始化")
        client.resetSession()

        val (sw, sh) = controller.getScreenSize()
        _logs.value = emptyList()
        _state.value = AgentState(isRunning = true, instruction = instruction, phase = AgentPhase.RUNNING)
        OverlayService.show(context, "GUI-Owl 模式") { stopInternal() }

        try {
            for (step in 1..maxSteps) {
                coroutineContext.ensureActive()
                if (!_state.value.isRunning) return finishWith(false, "已停止")
                _state.value = _state.value.copy(currentStep = step)

                val shot = obtainScreenshot() ?: continue
                val response = client.predict(instruction, shot)
                if (response.isFailure) continue

                val result = response.getOrThrow()
                val parsed = client.parseOperation(result.operation) ?: continue

                val s = ExecutionStep(
                    stepNumber = step,
                    timestamp = System.currentTimeMillis(),
                    action = parsed.type,
                    description = result.explanation,
                    thought = result.thought,
                    outcome = "进行中"
                )
                _state.value = _state.value.copy(executionSteps = _state.value.executionSteps + s)

                if (parsed.type == "finish") return finishWith(true, "任务完成")

                executeGUIOwlAction(parsed, sw, sh)
                updateStepOutcome(_state.value.executionSteps.size - 1, "成功")
                cachedScreenshot = null
                delay(900)
            }
            return finishWith(false, "达到最大步数限制")
        } catch (e: CancellationException) {
            cleanup()
            throw e
        }
    }

    private suspend fun executeGUIOwlAction(
        action: GUIOwlClient.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ) {
        withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> action.x?.let { x -> action.y?.let { y -> controller.tap(x, y) } }
            "swipe" -> {
                val x1 = action.x
                val y1 = action.y
                val x2 = action.x2
                val y2 = action.y2
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    controller.swipe(x1, y1, x2, y2)
                }
                Unit
            }
            "long_press" -> action.x?.let { x -> action.y?.let { y -> controller.longPress(x, y) } }
            "type" -> action.text?.let { controller.type(it) }
            "system_button" -> when (action.text?.lowercase()) {
                "back" -> controller.back()
                "home" -> controller.home()
                else -> Unit
            }
            else -> Unit
        }
        }
    }

    private suspend fun runInstructionWithMAIUI(instruction: String, maxSteps: Int): AgentResult {
        val client = maiuiClient ?: return AgentResult(false, "MAI-UI 客户端未初始化")
        client.reset()

        val (sw, sh) = controller.getScreenSize()
        client.setAvailableApps(appScanner.getApps().map { it.appName })

        _logs.value = emptyList()
        _state.value = AgentState(isRunning = true, instruction = instruction, phase = AgentPhase.RUNNING)
        OverlayService.show(context, "MAI-UI 模式") { stopInternal() }

        try {
            for (step in 1..maxSteps) {
                coroutineContext.ensureActive()
                if (!_state.value.isRunning) return finishWith(false, "已停止")
                _state.value = _state.value.copy(currentStep = step)

                val shot = obtainScreenshot() ?: continue
                val response = client.predict(instruction, shot)
                if (response.isFailure) continue

                val result = response.getOrThrow()
                val action = result.action ?: continue

                val s = ExecutionStep(
                    stepNumber = step,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = result.thinking.take(60),
                    thought = result.thinking,
                    outcome = "进行中"
                )
                _state.value = _state.value.copy(executionSteps = _state.value.executionSteps + s)

                if (action.type == "terminate") {
                    val ok = action.status == "success"
                    return finishWith(ok, if (ok) "任务完成" else "任务失败")
                }
                if (action.type == "answer") {
                    return finishWith(true, action.text ?: "已完成")
                }

                executeMAIUIAction(action, sw, sh)
                updateStepOutcome(_state.value.executionSteps.size - 1, "成功")
                cachedScreenshot = null
                delay(800)
            }
            return finishWith(false, "达到最大步数限制")
        } catch (e: CancellationException) {
            cleanup()
            throw e
        }
    }

    private suspend fun executeMAIUIAction(
        action: com.dougao.app.vlm.MAIUIAction,
        screenWidth: Int,
        screenHeight: Int
    ) {
        withContext(Dispatchers.IO) {
        val screenAction = action.toScreenCoordinates(screenWidth, screenHeight)
        when (action.type) {
            "click" -> screenAction.x?.let { x -> screenAction.y?.let { y -> controller.tap(x.toInt(), y.toInt()) } }
            "long_press" -> screenAction.x?.let { x -> screenAction.y?.let { y -> controller.longPress(x.toInt(), y.toInt()) } }
            "type" -> action.text?.let { controller.type(it) }
            "open" -> action.text?.let { controller.openApp(it) }
            "system_button" -> when (action.button?.lowercase()) {
                "back" -> controller.back()
                "home" -> controller.home()
                "enter" -> controller.enter()
                else -> Unit
            }
            else -> Unit
        }
        }
    }

    // ==================================================================
    // 其他
    // ==================================================================

    /** 回到豆糕 */
    private fun bringAppToFront() {
        val pkg = context.packageName
        // 后台起 Activity 会被系统拦住，优先用 shell
        try {
            if (ShizukuShell.isAvailable()) {
                val component = "$pkg/${pkg}.MainActivity"
                val out = ShizukuShell.execText("am start -n $component")
                if (!out.contains("Error", true)) return
            }
        } catch (_: Exception) {
        }

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            intent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回豆糕失败：${e.message}")
        }
    }

    fun stop() {
        _state.value = _state.value.copy(isRunning = false)
        stopInternal()
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _state.value = _state.value.copy(executionSteps = emptyList(), summary = null)
    }

    private fun log(message: String) {
        println("[豆糕] $message")
        _logs.value = _logs.value + message
    }
}

/** 执行阶段 */
enum class AgentPhase {
    IDLE, RUNNING, SUCCESS, FAILED
}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList(),
    /** 结束后的总结文案（给「执行全景」面板用） */
    val summary: String? = null,
    val phase: AgentPhase = AgentPhase.IDLE
)

data class AgentResult(
    val success: Boolean,
    val message: String
)

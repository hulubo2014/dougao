package com.dougao.app.agent

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 豆糕执行协议（对齐 zai-org/Open-AutoGLM）
 *
 * 模型的输出形态：
 * ```
 * <think>简单推理说明</think>
 * <answer>do(action="Tap", element=[500, 300])</answer>
 * ```
 * 或者直接一行 `do(...)` / `finish(message="...")`。
 *
 * 解析策略（参考 AndroidAutoGLM + Aries-AI 的实战结论）：
 * 1. 不强依赖 `<answer>` 标签 —— 模型经常漏写，在整段文本里找 `do(` / `finish(` 更鲁棒；
 * 2. 同时出现时**取最后一个** —— 模型在纠正上一步错误时会给两个动作，最后一个才是它真正想执行的；
 * 3. 括号配对用**手写计数状态机**（不是正则）—— 因为 `text="内容(含括号)"` 会破坏正则；
 * 4. 参数解析同时写入原 key 和小写 key，读取时大小写不敏感。
 */
object AutoGlmProtocol {

    /** 解析出来的动作 */
    data class ParsedAction(
        /** do / finish / unknown */
        val kind: String,
        /** Tap / Swipe / Type / Launch ... finish 时为 null */
        val name: String?,
        /** 原始参数字符串值（key 已统一小写一份） */
        val params: Map<String, String>,
        /** 原始动作文本，出错时展示给用户看 */
        val raw: String
    ) {
        fun str(key: String): String? = params[key]?.takeIf { it.isNotBlank() }

        fun point(key: String): Pair<Int, Int>? = parsePoint(params[key])

        fun screenPoint(key: String, w: Int, h: Int): Pair<Int, Int>? {
            val p = point(key) ?: return null
            return toScreen(p, w, h)
        }

        val isFinish: Boolean get() = kind == "finish"
        val isDo: Boolean get() = kind == "do"
    }

    // ------------------------------------------------------------------
    // 文本切分
    // ------------------------------------------------------------------

    /**
     * 把模型输出切成「思考」和「动作原文」两段。
     */
    fun splitThinking(text: String): Pair<String, String> {
        val trimmed = text.trim()

        // 1) finish( 优先（若是 finish，后面不会再有 do）
        val finishIdx = trimmed.indexOf("finish(")
        val doIdx = trimmed.indexOf("do(action=")

        val actionStart = when {
            finishIdx >= 0 && doIdx >= 0 -> maxOf(finishIdx, doIdx)
            finishIdx >= 0 -> finishIdx
            doIdx >= 0 -> doIdx
            else -> -1
        }

        if (actionStart >= 0) {
            var thinking = trimmed.substring(0, actionStart).trim()
            thinking = thinking
                .replace("<think>", "")
                .replace("</think>", "")
                .replace("<answer>", "")
                .trim()
            return thinking to trimmed.substring(actionStart).trim()
        }

        // 2) 退到 XML 标签
        val answerMatch = Regex("<answer>([\\s\\S]*?)</answer>").find(trimmed)
        if (answerMatch != null) {
            var thinking = trimmed.substring(0, answerMatch.range.first)
                .replace("<think>", "")
                .replace("</think>", "")
                .trim()
            return thinking to answerMatch.groupValues[1].trim()
        }

        // 3) 兜底：整段当动作
        return "" to trimmed
    }

    // ------------------------------------------------------------------
    // 动作解析
    // ------------------------------------------------------------------

    fun parse(text: String): ParsedAction {
        val (_, actionPart) = splitThinking(text)
        val target = actionPart.trim()

        if (target.startsWith("finish")) {
            val message = extractFinishMessage(target)
            return ParsedAction(
                kind = "finish",
                name = null,
                params = mapOf("message" to message),
                raw = target
            )
        }

        if (!target.startsWith("do")) {
            // 有些模型会直接给 {"action":"Tap",...}，也认一下
            return ParsedAction("unknown", null, emptyMap(), target.take(300))
        }

        val openIdx = target.indexOf('(')
        if (openIdx < 0) {
            return ParsedAction("unknown", null, emptyMap(), target.take(300))
        }

        val inner = extractBalanced(target, openIdx)
        val params = parseParams(inner)
        val name = params["action"]
            ?: return ParsedAction("unknown", null, params, target.take(300))

        return ParsedAction(
            kind = "do",
            name = name.trim(),
            params = params,
            raw = target.take(400)
        )
    }

    /** 取 `do(` 与它配对的右括号之间的内容（支持嵌套括号） */
    private fun extractBalanced(text: String, openIdx: Int): String {
        var depth = 0
        var inDouble = false
        var inSingle = false
        var i = openIdx

        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\'' && !inDouble -> inSingle = !inSingle
                !inDouble && !inSingle && c == '(' -> depth++
                !inDouble && !inSingle && c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openIdx + 1, i)
                }
            }
            i++
        }
        // 没配对 → 说明输出被截断了，把剩下的都给了
        return text.substring(openIdx + 1).trimEnd(')', ',', ' ')
    }

    /**
     * 解析形如 `action="Tap", element=[500,300], text="你好"` 的参数串。
     * 同时写入原 key 和 lower-case key。
     */
    private fun parseParams(args: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 数组： key = [1, 2] 或 key = (1,2)
        val listRegex = Regex("""(\w+)\s*=\s*[\[\(]([^\]\)]*)[\]\)]""")
        listRegex.findAll(args).forEach { m ->
            val key = m.groupValues[1]
            val raw = m.groupValues[2].trim()
            val normalized = raw.split(",")
                .mapNotNull { part -> part.trim().toFloatOrNull()?.let { it.toInt().toString() } }
                .joinToString(", ")
            put(result, key, "[$normalized]")
        }

        // 字符串： key = "xxx" 或 key = 'xxx'
        val strRegex = Regex("""(\w+)\s*=\s*"([\s\S]*?)"""")
        strRegex.findAll(args).forEach { m ->
            put(result, m.groupValues[1], m.groupValues[2])
        }
        val singleRegex = Regex("""(\w+)\s*=\s*'([\s\S]*?)'""")
        singleRegex.findAll(args).forEach { m ->
            put(result, m.groupValues[1], m.groupValues[2])
        }

        // 裸值： key = 123 / key = 3 seconds / key = up
        val bareRegex = Regex("""(\w+)\s*=\s*([^,"\)\]\}]+)""")
        bareRegex.findAll(args).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2].trim()
            if (value.isEmpty()) return@forEach
            if (value.startsWith("[") || value.startsWith("(")) return@forEach
            if (value.startsWith("\"") || value.startsWith("'")) return@forEach
            if (!result.containsKey(key)) put(result, key, value)
        }

        return result
    }

    private fun put(map: MutableMap<String, String>, key: String, value: String) {
        map[key] = value
        val lower = key.lowercase()
        if (!map.containsKey(lower)) map[lower] = value
    }

    private fun extractFinishMessage(text: String): String {
        val regex = Regex("""message\s*=\s*"([\s\S]*?)"""")
        regex.find(text)?.let { return it.groupValues[1].trim() }
        val singleRegex = Regex("""message\s*=\s*'([\s\S]*?)'""")
        singleRegex.find(text)?.let { return it.groupValues[1].trim() }
        // 无引号：finish(message=任务完成)
        val bare = Regex("""message\s*=\s*([^\)]*)""").find(text)?.groupValues?.get(1)?.trim()
        return bare?.trim('"', '\'', ' ') ?: ""
    }

    // ------------------------------------------------------------------
    // 坐标
    // ------------------------------------------------------------------

    /** 解析 [500, 300] / (500,300) / 500,300 */
    fun parsePoint(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removeSurrounding("[", "]").removeSurrounding("(", ")")
        val parts = cleaned.split(",").map { it.trim() }
        if (parts.size < 2) return null
        val x = parts[0].toFloatOrNull()?.toInt() ?: return null
        val y = parts[1].toFloatOrNull()?.toInt() ?: return null
        return x to y
    }

    /**
     * 0-999 相对坐标 -> 屏幕绝对像素。
     * Open-AutoGLM 用的是除以 1000（不是 999）。
     */
    fun toScreen(point: Pair<Int, Int>, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val rx = point.first.coerceIn(0, 999)
        val ry = point.second.coerceIn(0, 999)
        val x = (rx / 1000f * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        val y = (ry / 1000f * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        return x to y
    }

    /** Wait 的 duration 可能是 "3 seconds" / "3s" / "3000ms" / "3" */
    fun parseDurationMs(raw: String?, defaultMs: Long = 1000L): Long {
        if (raw.isNullOrBlank()) return defaultMs
        val lower = raw.lowercase().trim()

        Regex("""(\d+(?:\.\d+)?)""").find(lower)?.groupValues?.get(1)?.toDoubleOrNull()?.let { num ->
            return when {
                lower.contains("ms") -> num.toLong()
                lower.contains("min") -> (num * 60_000).toLong()
                // 纯数字或带 s/second：模型默认按秒理解
                else -> (num * 1000).toLong()
            }
        }
        return defaultMs
    }

    // ------------------------------------------------------------------
    // 动作的人话描述（给 UI 用）
    // ------------------------------------------------------------------

    fun describe(action: ParsedAction): String {
        if (action.isFinish) {
            return action.str("message")?.takeIf { it.isNotBlank() } ?: "任务完成"
        }
        return when (action.name?.lowercase()?.replace(" ", "")) {
            "launch" -> "打开「${action.str("app") ?: "应用"}」"
            "tap" -> "点击 ${action.str("element") ?: ""}"
            "doubletap" -> "双击 ${action.str("element") ?: ""}"
            "longpress" -> "长按 ${action.str("element") ?: ""}"
            "type", "typename" -> "输入「${action.str("text")?.take(20) ?: ""}」"
            "swipe" -> "滑动 ${action.str("start") ?: ""} → ${action.str("end") ?: ""}"
            "back" -> "返回上一页"
            "home" -> "回到桌面"
            "wait" -> "等待 ${action.str("duration") ?: "1秒"}"
            "take_over" -> "需要你手动操作"
            "interact" -> "等待你选择"
            "note" -> "记录当前页面"
            "call_api" -> "总结页面内容"
            else -> action.name ?: "未知操作"
        }
    }

    // ------------------------------------------------------------------
    // 系统提示词（改自 Open-AutoGLM 官方 prompts_zh）
    // ------------------------------------------------------------------

    fun buildSystemPrompt(installedApps: String): String {
        val dateStr = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
        val weekdayNames = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val cal = java.util.Calendar.getInstance()
        val weekday = weekdayNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]

        return """今天的日期是: $dateStr $weekday
你是「豆糕」，一个可以操作安卓手机的智能助手。你能看到手机屏幕的截图，并依次执行操作来帮用户完成任务。

你必须严格按照以下格式输出：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think}：你为什么选择这个操作的简短推理（一两句话即可）。
- {action}：本次要执行的具体操作指令，格式见下方。

可用操作指令：
- do(action="Launch", app="xxx")  启动应用。比在桌面上找图标快得多。
- do(action="Tap", element=[x,y])  点击屏幕上某个点。坐标从左上角 (0,0) 到右下角 (999,999)。
- do(action="Tap", element=[x,y], message="重要操作")  同上，但点击的是涉及支付、隐私、财产等敏感按钮时使用，会先请用户确认。
- do(action="Type", text="xxx")  在当前输入框输入文字。用之前请先 Tap 输入框让它获得焦点。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  滑动。用于滚动、翻页、下拉通知栏。
- do(action="Long Press", element=[x,y])  长按。
- do(action="Double Tap", element=[x,y])  双击。
- do(action="Back")  返回上一级 / 关闭弹窗。
- do(action="Home")  回到系统桌面。
- do(action="Wait", duration="3 seconds")  等待页面加载。
- do(action="Take_over", message="xxx")  需要用户协助（登录、验证码、人脸识别等）时使用。
- finish(message="xxx")  确认任务已经完成时使用。

必须遵守的规则：
1. 执行任何操作前，先看当前是不是目标 App，不是就先 Launch。
2. 进入无关页面先 Back；Back 没反应就点页面左上角返回键，或右上角 X 关闭。
3. 页面没加载出内容，最多连续 Wait 三次，还不行就 Back 重进。
4. 页面提示网络问题就点重新加载。
5. 在当前页找不到目标，先 Swipe 滑动查找。
6. 筛选条件（价格、时间等）如果没有完全符合的，可以放宽。
7. 严格按用户意图执行，允许多次搜索、反复滑动查找。
8. 选择日期时如果滑的方向离目标越来越远，就往反方向滑。
9. 页面上有多个可选项栏目时，逐个查找，不要在同一个栏目里反复找导致死循环。
10. 执行下一步前先确认上一步生效了。点了没反应可能是 App 慢，先等一下；还不行就换个位置重试；仍不行就跳过，并在 finish 里说明。
11. 滑动不生效时，调整起点、加大距离重试；也可能是已经滑到头了，往反方向滑。
12. 搜索没有结果时，返回上一级换个说法再搜；试三次仍无结果就 finish 并说明原因。
13. 结束任务前，一定回头检查任务是否完整准确地完成。有错选、漏选、多选，要返回去纠正。
14. 发送 / 提交类任务：Type 之后要 Tap 发送按钮，然后看下一步的屏幕确认确实发出去了（比如消息出现在聊天记录里、评论显示出来了），确认成功才能 finish。界面没变化就 Wait 重试，或者 finish 说明没生效。
15. **不要过早结束**。只有任务真的做完了才调用 finish。如果只是这一小步做完了，继续做下一步。
16. **做完了就立刻收手，绝不画蛇添足**。目标达成的那一秒马上调用 finish。不要再多点几下、多滑几次、多确认一遍、顺手打开别的页面 —— 多做的那几下既浪费时间，还可能把已经做好的事情搞坏。判断标准只有一条：「用户要我办的事，办成没有？」办成了，立刻 finish。
17. **finish 里的 message 就是给用户看的总结，必须写完整**：
    - 做事类任务：一句话说清结果，例如「已成功打开 QQ」「已把这条消息发给张三」。
    - 查资料 / 让豆糕阅读后回答的任务（例如「去某乎搜一下 XX 的优点」）：必须先把内容翻够，然后在 finish 里**分条列出结论**（优点、缺点、关键信息、你的判断），条与条之间换行。不要只回一句「已完成」，那是废话，用户要的是内容本身。
    - 结尾不要写「如有需要请随时告诉我」这类客套话，把篇幅留给真正有用的信息。

本机已安装的部分应用（Launch 时优先用这里的名字）：
$installedApps

坐标系统：左上角 (0,0) 到右下角 (999,999)。"""
    }
}

package com.dougao.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 会话里的一条消息。
 *
 * 一个「任务」= 一个独立的房间，房间里的对话按时间顺序堆在一起：
 *   用户说「打开抖音」→ AI 执行 → 用户发现做错了，点叉号停掉 →
 *   用户继续说「不是这个，要点右边那个」→ AI 带着前面这段历史继续改。
 *
 * 所以这里的消息不只是给人看的，也是下一次执行时要喂给模型的上下文。
 */
data class SessionMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,                       // "user" | "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val kind: String = KIND_TEXT
) {
    val isUser: Boolean get() = role == "user"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        put("text", text)
        put("timestamp", timestamp)
        put("kind", kind)
    }

    companion object {
        /** 普通文字消息 */
        const val KIND_TEXT = "text"

        /** 一轮执行的结果（任务完成 / 已停止 / 出错） */
        const val KIND_RESULT = "result"

        /** 出错 */
        const val KIND_ERROR = "error"

        fun fromJson(json: JSONObject): SessionMessage = SessionMessage(
            id = json.optString("id", UUID.randomUUID().toString()),
            role = json.optString("role", "user"),
            text = json.optString("text", ""),
            timestamp = json.optLong("timestamp", 0L),
            kind = json.optString("kind", KIND_TEXT)
        )
    }
}

/**
 * 一个任务会话（房间）
 */
data class TaskSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 房间里的完整对话流 */
    val messages: List<SessionMessage> = emptyList(),
    val status: SessionStatus = SessionStatus.IDLE,
    /** 最近一轮执行的步骤（给人看「执行全景」） */
    val steps: List<ExecutionStep> = emptyList(),
    val logs: List<String> = emptyList(),
    val pinned: Boolean = false
) {
    val formattedTime: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))

    /** 最后一次用户说的话（抽屉里当副标题用） */
    val lastUserText: String
        get() = messages.lastOrNull { it.isUser }?.text ?: ""

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("status", status.name)
        put("pinned", pinned)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        put("logs", JSONArray().apply { logs.forEach { put(it) } })
    }

    companion object {
        fun fromJson(json: JSONObject): TaskSession {
            val messagesArray = json.optJSONArray("messages") ?: JSONArray()
            val messages = mutableListOf<SessionMessage>()
            for (i in 0 until messagesArray.length()) {
                messages.add(SessionMessage.fromJson(messagesArray.getJSONObject(i)))
            }

            val stepsArray = json.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<ExecutionStep>()
            for (i in 0 until stepsArray.length()) {
                steps.add(ExecutionStep.fromJson(stepsArray.getJSONObject(i)))
            }

            val logsArray = json.optJSONArray("logs") ?: JSONArray()
            val logs = mutableListOf<String>()
            for (i in 0 until logsArray.length()) {
                logs.add(logsArray.optString(i, ""))
            }

            return TaskSession(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "新任务"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                messages = messages,
                status = try {
                    SessionStatus.valueOf(json.optString("status", "IDLE"))
                } catch (e: Exception) {
                    SessionStatus.IDLE
                },
                steps = steps,
                logs = logs,
                pinned = json.optBoolean("pinned", false)
            )
        }
    }
}

enum class SessionStatus {
    /** 还没执行过 */
    IDLE,

    /** 正在执行 */
    RUNNING,

    /** 上一轮执行完了 */
    DONE,

    /** 出错结束 */
    FAILED,

    /** 被用户中途停掉 —— 还可以继续在这个房间里纠错 */
    STOPPED
}

/**
 * 会话仓库：落盘到 filesDir/task_sessions.json，用 StateFlow 暴露给界面。
 */
class TaskSessionStore(private val context: Context) {

    private val _sessions = MutableStateFlow<List<TaskSession>>(emptyList())
    val sessions: StateFlow<List<TaskSession>> = _sessions

    private val file: File
        get() = File(context.filesDir, "task_sessions.json")

    /** 最多保留多少个会话 */
    private val maxSessions = 200

    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                _sessions.value = emptyList()
                return@withContext
            }
            val array = JSONArray(file.readText())
            val list = mutableListOf<TaskSession>()
            for (i in 0 until array.length()) {
                list.add(TaskSession.fromJson(array.getJSONObject(i)))
            }
            _sessions.value = sort(list)
        } catch (e: Exception) {
            e.printStackTrace()
            _sessions.value = emptyList()
        }
    }

    suspend fun save(session: TaskSession) = withContext(Dispatchers.IO) {
        try {
            val list = _sessions.value.toMutableList()
            val index = list.indexOfFirst { it.id == session.id }
            val stamped = session.copy(updatedAt = System.currentTimeMillis())
            if (index >= 0) list[index] = stamped else list.add(stamped)
            val trimmed = list.sortedByDescending { it.updatedAt }.take(maxSessions)
            _sessions.value = sort(trimmed)
            persist(trimmed)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        try {
            val list = _sessions.value.filter { it.id != id }
            _sessions.value = sort(list)
            persist(list)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        val target = _sessions.value.find { it.id == id } ?: return@withContext
        save(target.copy(pinned = pinned))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            _sessions.value = emptyList()
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 把「还在跑」的残影纠正成「已停止」（豆糕 1.3.2）。
     *
     * 什么时候需要它：任务执行到一半时 App 被强杀 / 划掉，
     * 收尾代码没来得及把 status 改回来，房间就一直停在 RUNNING。
     * 下次打开 App，首页会照着这个状态一直转圈 —— 用户看到就是「我明明关了它还在转」。
     *
     * App 刚启动时不可能有任务在跑，所以这里统一清一遍。
     */
    suspend fun resetRunningSessions() = withContext(Dispatchers.IO) {
        try {
            val list = _sessions.value
            if (list.none { it.status == SessionStatus.RUNNING }) return@withContext
            val fixed = list.map { s ->
                if (s.status == SessionStatus.RUNNING) s.copy(status = SessionStatus.STOPPED) else s
            }
            _sessions.value = sort(fixed)
            persist(fixed)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persist(list: List<TaskSession>) {
        try {
            val array = JSONArray().apply { list.forEach { put(it.toJson()) } }
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 置顶的排前面，其余按更新时间倒序 */
    private fun sort(list: List<TaskSession>): List<TaskSession> =
        list.sortedWith(
            compareByDescending<TaskSession> { it.pinned }
                .thenByDescending { it.updatedAt }
        )
}

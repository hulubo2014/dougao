package com.dougao.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 执行步骤记录
 */
data class ExecutionStep(
    val stepNumber: Int,
    val timestamp: Long,
    val action: String,
    val description: String,
    val thought: String,
    val outcome: String,  // A=成功, B=部分成功, C=失败
    val screenshotPath: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepNumber", stepNumber)
        put("timestamp", timestamp)
        put("action", action)
        put("description", description)
        put("thought", thought)
        put("outcome", outcome)
        put("screenshotPath", screenshotPath ?: "")
    }

    companion object {
        fun fromJson(json: JSONObject): ExecutionStep = ExecutionStep(
            stepNumber = json.optInt("stepNumber", 0),
            timestamp = json.optLong("timestamp", 0),
            action = json.optString("action", ""),
            description = json.optString("description", ""),
            thought = json.optString("thought", ""),
            outcome = json.optString("outcome", ""),
            screenshotPath = json.optString("screenshotPath", "").ifEmpty { null }
        )
    }
}

/**
 * 执行记录
 */
data class ExecutionRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val instruction: String,
    val startTime: Long,
    val endTime: Long = 0,
    val status: ExecutionStatus = ExecutionStatus.RUNNING,
    val steps: List<ExecutionStep> = emptyList(),
    val logs: List<String> = emptyList(),
    val resultMessage: String = ""
) {
    val duration: Long get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime

    val formattedStartTime: String get() {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(startTime))
    }

    val formattedDuration: String get() {
        val seconds = duration / 1000
        return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("instruction", instruction)
        put("startTime", startTime)
        put("endTime", endTime)
        put("status", status.name)
        put("resultMessage", resultMessage)
        put("steps", JSONArray().apply {
            steps.forEach { put(it.toJson()) }
        })
        put("logs", JSONArray().apply {
            logs.forEach { put(it) }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): ExecutionRecord {
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
            return ExecutionRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", ""),
                instruction = json.optString("instruction", ""),
                startTime = json.optLong("startTime", 0),
                endTime = json.optLong("endTime", 0),
                status = try {
                    ExecutionStatus.valueOf(json.optString("status", "COMPLETED"))
                } catch (e: Exception) {
                    ExecutionStatus.COMPLETED
                },
                steps = steps,
                logs = logs,
                resultMessage = json.optString("resultMessage", "")
            )
        }
    }
}

enum class ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED
}

/**
 * 执行记录仓库
 */
class ExecutionRepository(private val context: Context) {

    private val historyFile: File
        get() = File(context.filesDir, "execution_history.json")

    /**
     * 获取所有记录
     */
    suspend fun getAllRecords(): List<ExecutionRecord> = withContext(Dispatchers.IO) {
        try {
            if (!historyFile.exists()) return@withContext emptyList()
            val json = historyFile.readText()
            val array = JSONArray(json)
            val records = mutableListOf<ExecutionRecord>()
            for (i in 0 until array.length()) {
                records.add(ExecutionRecord.fromJson(array.getJSONObject(i)))
            }
            records.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取单条记录
     */
    suspend fun getRecord(id: String): ExecutionRecord? = withContext(Dispatchers.IO) {
        getAllRecords().find { it.id == id }
    }

    /**
     * 保存记录
     */
    suspend fun saveRecord(record: ExecutionRecord) = withContext(Dispatchers.IO) {
        try {
            val records = getAllRecords().toMutableList()
            val existingIndex = records.indexOfFirst { it.id == record.id }
            if (existingIndex >= 0) {
                records[existingIndex] = record
            } else {
                records.add(0, record)
            }
            // 只保留最近100条记录
            val trimmedRecords = records.take(100)
            val array = JSONArray().apply {
                trimmedRecords.forEach { put(it.toJson()) }
            }
            historyFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除记录
     */
    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        try {
            val records = getAllRecords().filter { it.id != id }
            val array = JSONArray().apply {
                records.forEach { put(it.toJson()) }
            }
            historyFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清空所有记录
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            historyFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

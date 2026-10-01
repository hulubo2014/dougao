package com.dougao.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * 文件工作区管理器（豆糕新增）
 *
 * 用户通过系统文件选择器授权"一个文件夹"或"一个文件"后，
 * 这里负责记住这些授权，并提供统一的读写入口。
 *
 * 关键点：整个流程 **完全不依赖屏幕操作**，
 * AI 直接读文件内容、直接写回文件，比"看着屏幕点来点去"快得多也稳得多。
 */
class WorkspaceManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("dougao_workspace", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WorkspaceEntry>> = _entries

    // ------------------------------------------------------------------
    // 授权记录
    // ------------------------------------------------------------------

    private fun load(): List<WorkspaceEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        val list = mutableListOf<WorkspaceEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                WorkspaceEntry.fromJson(obj)?.let { list.add(it) }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "读取工作区失败", e)
        }
        return list
    }

    private fun persist(list: List<WorkspaceEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("entries", arr.toString()).apply()
        _entries.value = list
    }

    /**
     * 记录一个已授权的文件 / 文件夹
     */
    fun addEntry(uri: Uri, isDirectory: Boolean): WorkspaceEntry? {
        val name = resolveDisplayName(uri) ?: uri.lastPathSegment ?: "未命名"
        val existed = _entries.value.firstOrNull { it.uri == uri.toString() }
        if (existed != null) return existed

        val entry = WorkspaceEntry(
            uri = uri.toString(),
            displayName = name,
            isDirectory = isDirectory
        )
        persist(_entries.value + entry)
        return entry
    }

    fun removeEntry(entry: WorkspaceEntry) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(entry.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 权限可能已经不存在，忽略
        }
        persist(_entries.value.filterNot { it.uri == entry.uri })
    }

    fun clearAll() {
        _entries.value.forEach { entry ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(entry.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        persist(emptyList())
    }

    private fun resolveDisplayName(uri: Uri): String? = try {
        DocumentFile.fromTreeUri(context, uri)?.name
            ?: DocumentFile.fromSingleUri(context, uri)?.name
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "WorkspaceManager"

        @Volatile
        private var instance: WorkspaceManager? = null

        fun get(context: Context): WorkspaceManager =
            instance ?: synchronized(this) {
                instance ?: WorkspaceManager(context.applicationContext).also { instance = it }
            }
    }
}

package com.dougao.app.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dougao.app.data.WorkspaceEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 文件条目（用于界面展示与 AI 读取）
 */
data class FileNode(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val relativePath: String
) {
    val isTextLike: Boolean
        get() = NAME_EXTENSIONS.contains(extensionOf(name))

    val extension: String get() = extensionOf(name)

    companion object {
        /** 可以直接读写的文本类扩展名 */
        val NAME_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "xml", "yml", "yaml", "csv", "tsv", "log", "ini", "conf", "cfg", "properties",
            "kt", "java", "js", "ts", "jsx", "tsx", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "html", "htm", "css", "scss", "less", "vue", "sql", "sh", "bat", "ps1", "gradle", "kts", "toml", "env",
            "srt", "ass", "vtt", "tex", "m", "mm", "dart", "lua", "r", "pl", "asm", "makefile", "gitignore"
        )

        fun extensionOf(name: String): String {
            val idx = name.lastIndexOf('.')
            return if (idx <= 0 || idx == name.length - 1) "" else name.substring(idx + 1).lowercase()
        }
    }
}

/**
 * 文件读写网关（豆糕新增）
 *
 * 基于 SAF（Storage Access Framework），**不需要存储权限、不需要 Root**，
 * 只要用户手动授权过一次文件夹，后面 AI 就可以直接读写里面的文件。
 */
class FileGateway(private val context: Context) {

    private val resolver = context.contentResolver

    /** 记住每个文件上次读取时用的编码，写回时保持一致，避免中文乱码 */
    private val encodingCache = HashMap<String, String>()

    companion object {
        /** 单个文本文件读取上限（20MB） */
        private const val MAX_TEXT_BYTES = 20 * 1024 * 1024L
    }

    // ------------------------------------------------------------------
    // 定位
    // ------------------------------------------------------------------

    fun rootOf(entry: WorkspaceEntry): DocumentFile? = try {
        val uri = Uri.parse(entry.uri)
        if (entry.isDirectory) {
            DocumentFile.fromTreeUri(context, uri) ?: DocumentFile.fromSingleUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 把相对路径解析成 DocumentFile
     * @param relativePath 例如 "src/main/a.txt"；空串表示根
     */
    fun resolve(entry: WorkspaceEntry, relativePath: String): DocumentFile? {
        val root = rootOf(entry) ?: return null
        val clean = relativePath.trim().trim('/', '\\')
        if (clean.isEmpty() || clean == ".") return root

        var current = root
        for (segment in clean.split('/', '\\')) {
            if (segment.isEmpty() || segment == ".") continue
            if (!current.isDirectory) return null
            current = findChild(current, segment) ?: return null
        }
        return current
    }

    private fun findChild(parent: DocumentFile, name: String): DocumentFile? {
        // 先精确匹配，再忽略大小写匹配，提升容错
        return try {
            parent.findFile(name)
                ?: parent.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }
        } catch (e: Exception) {
            null
        }
    }

    fun relativePathOf(entry: WorkspaceEntry, uri: Uri): String {
        val rootUri = entry.uri
        return if (uri.toString() == rootUri) "" else uri.toString()
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    fun list(entry: WorkspaceEntry, relativePath: String): List<FileNode> {
        val dir = resolve(entry, relativePath) ?: return emptyList()
        if (!dir.isDirectory) {
            return listOf(
                FileNode(
                    uri = dir.uri,
                    name = dir.name ?: entry.displayName,
                    isDirectory = false,
                    size = dir.length(),
                    lastModified = dir.lastModified(),
                    relativePath = relativePath
                )
            )
        }
        val base = relativePath.trim('/')
        return try {
            dir.listFiles()
                .filter { it.name != null }
                .map { f ->
                    FileNode(
                        uri = f.uri,
                        name = f.name ?: "",
                        isDirectory = f.isDirectory,
                        size = f.length(),
                        lastModified = f.lastModified(),
                        relativePath = if (base.isEmpty()) f.name!! else "$base/${f.name}"
                    )
                }
                .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 生成目录树文本（给 AI 看），限制深度与节点数避免超长
     */
    fun treeText(entry: WorkspaceEntry, relativePath: String = "", maxDepth: Int = 3, maxNodes: Int = 300): String {
        val sb = StringBuilder()
        var count = 0

        fun walk(dir: DocumentFile, path: String, depth: Int) {
            if (depth > maxDepth || count >= maxNodes) return
            val children = try {
                dir.listFiles().filter { it.name != null }
                    .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name!!.lowercase() })
            } catch (e: Exception) {
                return
            }
            for (child in children) {
                if (count >= maxNodes) {
                    sb.append("... (已省略更多文件)\n")
                    return
                }
                count++
                val name = child.name ?: continue
                val childPath = if (path.isEmpty()) name else "$path/$name"
                val indent = "  ".repeat(depth)
                if (child.isDirectory) {
                    sb.append("$indent$name/\n")
                    walk(child, childPath, depth + 1)
                } else {
                    val sizeKb = child.length() / 1024
                    sb.append("$indent$name  (${sizeKb}KB)\n")
                }
            }
        }

        val start = resolve(entry, relativePath) ?: return "(无法访问该目录)"
        if (!start.isDirectory) {
            return "${entry.displayName} (${start.length()} 字节)"
        }
        sb.append("${entry.displayName}/${if (relativePath.isNotBlank()) relativePath else ""}\n")
        walk(start, relativePath.trim('/'), 1)
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 读取文件文本。自动识别 UTF-8 / GBK，避免中文乱码。
     */
    fun readText(uri: Uri, maxChars: Int = 120_000): String {
        val bytes = readBytes(uri) ?: return "(无法读取文件)"
        // 安全阀：超大文件直接拒绝，避免把手机内存吃爆
        if (bytes.size > MAX_TEXT_BYTES) {
            return "(文件太大，${bytes.size / 1024 / 1024}MB，已跳过读取)"
        }
        val (text, encoding) = decodeSmart(bytes)
        encodingCache[uri.toString()] = encoding
        return if (text.length > maxChars) {
            text.substring(0, maxChars) + "\n... (内容过长，已截断，共 ${text.length} 字)"
        } else {
            text
        }
    }

    fun readBytes(uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { it.readBytesCompat() }
    } catch (e: Exception) {
        null
    }

    private fun InputStream.readBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 智能解码：先按 UTF-8 严格解码，失败则回退 GBK
     */
    private fun decodeSmart(bytes: ByteArray): Pair<String, String> {
        // 去掉 BOM
        val (data, bomEncoding) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                bytes.copyOfRange(3, bytes.size) to "UTF-8"
            else -> bytes to null
        }

        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(data)).toString() to (bomEncoding ?: "UTF-8")
        } catch (e: Exception) {
            try {
                String(data, charset("GBK")) to "GBK"
            } catch (e2: Exception) {
                String(data, Charsets.UTF_8) to "UTF-8"
            }
        }
    }

    /**
     * 读取 Office / PDF 之类的文件时尽量抽取纯文本（只读，不支持写回）
     */
    fun readDocumentText(uri: Uri, name: String): String? {
        val ext = FileNode.extensionOf(name)
        val bytes = readBytes(uri) ?: return null
        return when (ext) {
            "docx" -> extractDocx(bytes)
            "xlsx" -> extractXlsx(bytes)
            "pptx" -> extractPptx(bytes)
            else -> null
        }
    }

    private fun extractDocx(bytes: ByteArray): String? {
        return try {
            val xml = readZipEntry(bytes, "word/document.xml") ?: return null
            val out = StringBuilder()
            val paraRegex = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
            val textRegex = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            for (p in paraRegex.findAll(xml)) {
                val line = textRegex.findAll(p.value).joinToString("") { it.groupValues[1] }
                out.append(unescapeXml(line)).append("\n")
            }
            if (out.isEmpty()) {
                // 兜底：直接抓所有 w:t
                unescapeXml(textRegex.findAll(xml).joinToString("") { it.groupValues[1] })
            } else {
                out.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractXlsx(bytes: ByteArray): String? {
        return try {
            val shared = readZipEntry(bytes, "xl/sharedStrings.xml")
            val strings = mutableListOf<String>()
            if (shared != null) {
                Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(shared).forEach { si ->
                    val text = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(si.groupValues[1])
                        .joinToString("") { it.groupValues[1] }
                    strings.add(unescapeXml(text))
                }
            }
            val sheet = readZipEntry(bytes, "xl/worksheets/sheet1.xml") ?: return null
            val out = StringBuilder()
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(sheet).forEach { row ->
                val cells = mutableListOf<String>()
                Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(row.groupValues[1])
                    .forEach { c ->
                        val attrs = c.groupValues[1]
                        val inner = c.groupValues[2]
                        val isShared = attrs.contains("t=\"s\"")
                        val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                            .find(inner)?.groupValues?.get(1) ?: ""
                        cells.add(
                            if (isShared) {
                                strings.getOrNull(value.toIntOrNull() ?: -1) ?: value
                            } else {
                                unescapeXml(value)
                            }
                        )
                    }
                if (cells.any { it.isNotBlank() }) {
                    out.append(cells.joinToString("\t")).append("\n")
                }
            }
            out.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPptx(bytes: ByteArray): String? {
        return try {
            val sb = StringBuilder()
            var idx = 1
            while (idx <= 200) {
                val xml = readZipEntry(bytes, "ppt/slides/slide$idx.xml") ?: break
                val text = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml).joinToString("") { it.groupValues[1] }
                sb.append("【第 ").append(idx).append(" 页】")
                    .append(unescapeXml(text)).append("\n")
                idx++
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 zip（Office 文件本质就是 zip）中读取指定条目
     */
    private fun readZipEntry(bytes: ByteArray, entryName: String): String? {
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        val data = zis.readBytesCompat()
                        return data.toString(Charsets.UTF_8)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            // 忽略，返回 null
        }
        return null
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    fun writeText(uri: Uri, text: String): Boolean {
        return try {
            val encoding = encodingCache[uri.toString()] ?: "UTF-8"
            val bytes = if (encoding.equals("GBK", ignoreCase = true)) {
                text.toByteArray(charset("GBK"))
            } else {
                text.toByteArray(Charsets.UTF_8)
            }

            // 优先请求"截断写入"；个别网盘类 Provider 不支持 "wt" 模式，则退回默认模式
            val stream = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                null
            } ?: resolver.openOutputStream(uri) ?: return false

            stream.use { out ->
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("FileGateway", "写入失败: ${e.message}", e)
            false
        }
    }

    /**
     * 在目录下新建文件；已存在则返回已存在的文件
     */
    fun createFile(parent: DocumentFile, name: String, mime: String = "text/plain"): DocumentFile? = try {
        if (!parent.isDirectory) null
        else {
            parent.findFile(name) ?: parent.createFile(mime, name)
        }
    } catch (e: Exception) {
        null
    }

    fun createDirectory(parent: DocumentFile, name: String): DocumentFile? = try {
        if (!parent.isDirectory) null
        else parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
    } catch (e: Exception) {
        null
    }

    fun delete(target: DocumentFile): Boolean = try {
        target.delete()
    } catch (e: Exception) {
        false
    }

    fun rename(target: DocumentFile, newName: String): Boolean = try {
        target.renameTo(newName)
    } catch (e: Exception) {
        false
    }

    /**
     * 确保父目录存在，返回写好的文件（用于 AI 按相对路径写文件）
     */
    fun ensurePath(entry: WorkspaceEntry, relativePath: String): DocumentFile? {
        val root = rootOf(entry) ?: return null
        val clean = relativePath.trim().trim('/', '\\')
        if (clean.isEmpty()) return null
        val segments = clean.split('/', '\\').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        var current = root
        for (i in 0 until segments.size - 1) {
            val dirName = segments[i]
            val next = findChild(current, dirName)
                ?: createDirectory(current, dirName)
                ?: return null
            if (!next.isDirectory) return null
            current = next
        }
        val fileName = segments.last()
        return findChild(current, fileName) ?: createFile(current, fileName)
    }

    fun exists(entry: WorkspaceEntry, relativePath: String): Boolean = resolve(entry, relativePath) != null
}

package com.dougao.app.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dougao.app.App
import com.dougao.app.IShellService
import com.dougao.app.service.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备控制器
 *
 * 执行优先级（豆糕重构）：
 *   1. [ShizukuShell] —— `Shizuku.newProcess` 起独立 shell 进程，**能从 stdout 读二进制**，
 *      截图不再落盘、不再踩权限。这是主通道。
 *   2. AIDL UserService（[ShellService]）—— 兼容旧环境，只在 1 不可用时兜底。
 *   3. 本地 `Runtime.exec` —— 无权限时的最后尝试（基本不会成功，仅保证不崩溃）。
 *
 * 截图主通道：`screencap -p` 直接读 stdout 原始 PNG 字节。
 * 这是参考 AndroidAutoGLM / Aries-AI 后的结论 —— 它们都不落盘。
 */
class DeviceController(private val context: Context? = null) {

    companion object {
        /**
         * 最终兜底落盘路径。
         * App 自己的外部私有目录：shell 写得进、App 读得到，没有 SELinux 拦截。
         */
        private const val FALLBACK_SCREENSHOT_NAME = "dougao_screen.png"

        /** 截图最大像素，超过就缩放，避免内存爆掉 */
        private const val MAX_CAPTURE_PIXELS = 16_000_000L

        /** 发模型前的最大边（越大越准，越小越快；1024 是业界经验值） */
        private const val MODEL_IMAGE_MAX_DIM = 1024
    }

    private var appExternalDir: String? = null

    fun setExternalDir(dir: File?) {
        appExternalDir = dir?.absolutePath
    }

    /** 兼容旧调用（新实现不再需要缓存目录，截图不落盘） */
    fun setCacheDir(dir: File) {
        if (appExternalDir == null) appExternalDir = dir.absolutePath
    }

    private var shellService: IShellService? = null
    private var serviceBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.dougao.app", ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
        }
    }

    fun bindService() {
        if (!isShizukuAvailable()) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Shizuku 是否已授权 */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 命令通道是否就绪（Shizuku 直连 或 AIDL 服务任一可用） */
    fun isAvailable(): Boolean {
        return ShizukuShell.isAvailable() || (serviceBound && shellService != null)
    }

    enum class ShizukuPrivilegeLevel { NONE, ADB, ROOT }

    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) return ShizukuPrivilegeLevel.NONE
        return try {
            val uid = Shizuku.getUid()
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            ShizukuPrivilegeLevel.NONE
        }
    }

    // ------------------------------------------------------------------
    // 命令执行
    // ------------------------------------------------------------------

    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 执行 shell 并返回文本。优先 Shizuku 直连，其次 AIDL 服务，最后本地。
     */
    private fun exec(command: String): String {
        if (ShizukuShell.isAvailable()) {
            val r = ShizukuShell.execResult(command)
            if (r.exitCode != ShizukuShell.EXIT_EXCEPTION) return r.combinedText()
        }
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            execLocal(command)
        }
    }

    /** 执行 shell，只关心成败 */
    private fun execOk(command: String): Boolean {
        if (ShizukuShell.isAvailable()) {
            val r = ShizukuShell.execResult(command)
            if (r.exitCode != ShizukuShell.EXIT_EXCEPTION) return r.isOk
        }
        return try {
            val out = shellService?.exec(command) ?: execLocal(command)
            !out.contains("Error", ignoreCase = true) &&
                    !out.contains("denied", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 800) {
        exec("input swipe $x $y $x $y $durationMs")
    }

    fun doubleTap(x: Int, y: Int) {
        exec("input tap $x $y")
        try {
            Thread.sleep(90)
        } catch (_: InterruptedException) {
        }
        exec("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 400) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * 输入文本。
     *
     * 参考 Open-AutoGLM / AndroidAutoGLM 的踩坑结论：
     * `input text` 对中文和特殊字符几乎必挂；这里分两条路 ——
     * 纯 ASCII 走 input text，含非 ASCII 走「系统剪贴板 + 粘贴键」，最后再兜一层内置广播 IME。
     */
    fun type(text: String) {
        if (text.isEmpty()) return
        val hasNonAscii = text.any { it.code > 127 }

        if (!hasNonAscii) {
            val escaped = text.replace("'", "'\\''")
            val r = exec("input text '$escaped'")
            if (!r.contains("Error", ignoreCase = true) && !r.contains("Exception")) return
        }
        typeViaClipboard(text)
    }

    private fun typeViaClipboard(text: String) {
        if (clipboardManager != null) {
            try {
                val latch = CountDownLatch(1)
                var clipboardSet = false

                mainHandler.post {
                    try {
                        val clip = ClipData.newPlainText("dougao_input", text)
                        clipboardManager?.setPrimaryClip(clip)
                        clipboardSet = true
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }

                if (latch.await(1, TimeUnit.SECONDS) && clipboardSet) {
                    Thread.sleep(120)
                    // KEYCODE_PASTE = 279
                    exec("input keyevent 279")
                    return
                }
            } catch (_: Exception) {
            }
        }

        // 文字类输入兜底：内置广播 IME（若有安装）
        val escaped = text.replace("\"", "\\\"")
        val broadcastResult = exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
        if (broadcastResult.contains("result=0")) return
    }

    fun back() {
        exec("input keyevent 4")
    }

    fun home() {
        exec("input keyevent 3")
    }

    fun enter() {
        exec("input keyevent 66")
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,
        val isFallback: Boolean = false,
        val errorDetail: String? = null
    )

    /**
     * 截图（豆糕重构版）
     *
     * 顺序：
     *   1. `Shizuku.newProcess` 直接跑 `screencap -p`，从 stdout 读 PNG 原始字节 —— 最快最稳；
     *   2. 落盘到 App 外部私有目录再读 —— 兼容极老环境；
     *   3. 全部失败返回带原因的黑屏占位图（不再默默假装成功）。
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        var lastError: String? = null

        repeat(2) { round ->
            // 通道 1：stdout 直读（主通道）
            captureViaStdout()?.let { bytes ->
                decodeBitmap(bytes)?.let { return@withContext ScreenshotResult(it) }
            }

            // 通道 2：落盘再读（兜底）
            val path = screenshotFallbackPath()
            val fileAttempt = tryCaptureToFile(path)
            fileAttempt?.let { return@withContext ScreenshotResult(it) }
            if (fileAttempt == null) lastError = lastCaptureError ?: lastError

            if (round == 0) delay(120)
        }

        val detail = lastError ?: "截屏通道均不可用（请确认 Shizuku 已启动并授权）"
        println("[DeviceController] 截图失败：$detail")
        createFallbackScreenshot(isSensitive = false, detail = detail)
    }

    /** 记录最近一次失败原因 */
    private var lastCaptureError: String? = null

    /**
     * 主通道：Shizuku 直连跑 screencap -p，读 stdout。
     */
    private fun captureViaStdout(): ByteArray? {
        if (!ShizukuShell.isAvailable()) {
            lastCaptureError = "Shizuku 不可用（未启动或未授权）"
            return null
        }

        val result = ShizukuShell.execResult("screencap -p", binary = true)
        val err = result.stderrText()

        // 敏感页面（FLAG_SECURE）会让 screencap 返回非 0 并打印 Status: -1
        if (err.contains("Status: -1") || err.contains("Failed", ignoreCase = true)) {
            lastCaptureError = "系统阻止截图（可能是支付/密码页面）"
            return null
        }

        if (result.exitCode == ShizukuShell.EXIT_NO_BINDER) {
            lastCaptureError = "Shizuku binder 已断开，请重新启动 Shizuku"
            return null
        }
        if (result.exitCode == ShizukuShell.EXIT_NO_PERMISSION) {
            lastCaptureError = "豆糕还没有拿到 Shizuku 权限"
            return null
        }

        val bytes = result.stdout
        if (bytes.isEmpty()) {
            lastCaptureError = "screencap 没有输出数据（exit=${result.exitCode}）${if (err.isNotBlank()) " · $err" else ""}"
            return null
        }
        // PNG 头校验，防止把错误文本当成图片
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte()) {
            lastCaptureError = "screencap 输出不是合法图片"
            return null
        }
        lastCaptureError = null
        return bytes
    }

    private fun screenshotFallbackPath(): String {
        val dir = appExternalDir
        return if (dir != null) "$dir/$FALLBACK_SCREENSHOT_NAME" else "/data/local/tmp/$FALLBACK_SCREENSHOT_NAME"
    }

    /**
     * 兜底通道：写到文件再读回。
     */
    private fun tryCaptureToFile(path: String): Bitmap? {
        try {
            File(path).takeIf { it.exists() }?.delete()
        } catch (_: Exception) {
        }

        val output = exec("screencap -p $path")

        val blocked = output.contains("Status: -1") ||
                output.contains("Failed") ||
                output.contains("Permission denied") ||
                output.contains("No such file") ||
                output.contains("denied", ignoreCase = true)
        if (blocked) {
            lastCaptureError = output.trim().take(200).ifBlank { "落盘截图被拒绝" }
            return null
        }

        // 直接读
        try {
            val file = File(path)
            if (file.exists() && file.canRead() && file.length() > 0) {
                BitmapFactory.decodeFile(path)?.let { return it }
            }
        } catch (_: Exception) {
        }

        // 分块取回
        readBitmapViaShell(path)?.let { return it }

        lastCaptureError = "截图文件无法读取：$path"
        return null
    }

    /** 通过 shell 分块 base64 把文件取回来（Binder 单次 1MB 上限） */
    private fun readBitmapViaShell(path: String): Bitmap? {
        return try {
            val sizeText = exec("wc -c < $path")
            val size = sizeText.filter { it.isDigit() }.toLongOrNull() ?: return null
            if (size <= 0 || size > 40L * 1024 * 1024) return null

            val chunk = 256 * 1024
            val out = ByteArrayOutputStream()
            var index = 0
            while (index.toLong() * chunk < size) {
                val b64 = exec("dd if=$path bs=$chunk skip=$index count=1 2>/dev/null | base64")
                val clean = b64.filter { !it.isWhitespace() }
                if (clean.isEmpty()) break
                val bytes = try {
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    return null
                }
                if (bytes.isEmpty()) break
                out.write(bytes)
                index++
            }
            val data = out.toByteArray()
            if (data.isEmpty()) null else BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return try {
            // 先只读尺寸，超大图先缩放，避免 OOM
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return null

            val pixels = w.toLong() * h.toLong()
            val decodeOptions = BitmapFactory.Options()
            if (pixels > MAX_CAPTURE_PIXELS) {
                var sample = 1
                while (pixels / (sample.toLong() * sample) > MAX_CAPTURE_PIXELS) sample *= 2
                decodeOptions.inSampleSize = sample
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    private fun createFallbackScreenshot(isSensitive: Boolean, detail: String? = null): ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ScreenshotResult(
            bitmap = bitmap,
            isSensitive = isSensitive,
            isFallback = true,
            errorDetail = detail
        )
    }

    /** 保留旧接口 */
    suspend fun screenshot(): Bitmap? = screenshotWithFallback().bitmap

    /**
     * 把 Bitmap 压成发给模型的 base64（JPEG）。
     *
     * 参考 AndroidAutoGLM：最大边压到 1024、质量 80。
     * 这一步做完，单张图大概 100~200KB，比原图小一个数量级，直接决定每步快不快。
     */
    fun toModelBase64(bitmap: Bitmap): String {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxDim > MODEL_IMAGE_MAX_DIM) {
            val scale = MODEL_IMAGE_MAX_DIM.toFloat() / maxDim
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bitmap) scaled.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------
    // 屏幕信息
    // ------------------------------------------------------------------

    fun getScreenSize(): Pair<Int, Int> {
        val output = exec("wm size")
        val match = Regex("(\\d+)x(\\d+)").find(output)
        val (physicalWidth, physicalHeight) = if (match != null) {
            val (w, h) = match.destructured
            Pair(w.toInt(), h.toInt())
        } else {
            Pair(1080, 2400)
        }

        val orientation = getScreenOrientation()
        return if (orientation == 1 || orientation == 3) {
            Pair(physicalHeight, physicalWidth)
        } else {
            Pair(physicalWidth, physicalHeight)
        }
    }

    private fun getScreenOrientation(): Int {
        val output = exec("dumpsys window displays | grep mCurrentOrientation")
        val match = Regex("mCurrentOrientation=(\\d)").find(output)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 当前前台应用包名（给模型当上下文用） */
    fun getCurrentAppPackage(): String? {
        return try {
            val output = exec("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
            // 形如：mCurrentFocus=Window{abc u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}
            val match = Regex("(?:u0\\s+)?([a-zA-Z0-9_.]+)/").find(output)
            match?.groupValues?.get(1)?.takeIf { it.contains(".") }
        } catch (e: Exception) {
            null
        }
    }

    /** 包名 -> 应用名（查不到就返回包名） */
    fun getAppLabel(packageName: String): String {
        return try {
            val pm = context?.packageManager ?: App.getInstance().packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // ------------------------------------------------------------------
    // 打开应用
    // ------------------------------------------------------------------

    private val packageMap = mapOf(
        "settings" to "com.android.settings",
        "设置" to "com.android.settings",
        "chrome" to "com.android.chrome",
        "浏览器" to "com.android.browser",
        "camera" to "com.android.camera",
        "相机" to "com.android.camera",
        "phone" to "com.android.dialer",
        "电话" to "com.android.dialer",
        "contacts" to "com.android.contacts",
        "联系人" to "com.android.contacts",
        "messages" to "com.android.mms",
        "短信" to "com.android.mms",
        "gallery" to "com.android.gallery3d",
        "相册" to "com.android.gallery3d",
        "clock" to "com.android.deskclock",
        "时钟" to "com.android.deskclock",
        "calculator" to "com.android.calculator2",
        "计算器" to "com.android.calculator2",
        "calendar" to "com.android.calendar",
        "日历" to "com.android.calendar",
        "files" to "com.android.documentsui",
        "文件" to "com.android.documentsui"
    )

    /**
     * 打开 App。
     *
     * 参考 AndroidAutoGLM 的三级兜底：
     *   am start -p 包名 → resolve-activity 拿组件再 am start -n → monkey。
     * 单用 monkey 在部分机型（尤其国产 ROM）会失败，所以补齐前两级。
     */
    fun openApp(appNameOrPackage: String): Boolean {
        val lowerName = appNameOrPackage.lowercase().trim()
        val packageName: String = when {
            appNameOrPackage.contains(".") -> appNameOrPackage
            packageMap.containsKey(lowerName) -> packageMap[lowerName]!!
            else -> {
                val scanner = App.getInstance().appScanner
                val found = scanner.findPackage(appNameOrPackage)
                found ?: appNameOrPackage
            }
        }

        // 1) am start -p
        val out1 = exec(
            "am start --user 0 -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER -p $packageName"
        )
        if (!isLaunchFailure(out1)) return true

        // 2) resolve-activity -> am start -n
        val resolveOut = exec(
            "cmd package resolve-activity --brief --user 0 " +
                    "-c android.intent.category.LAUNCHER $packageName"
        )
        val component = parseResolveComponent(resolveOut)
        if (component != null) {
            val out2 = exec("am start --user 0 -n $component")
            if (!isLaunchFailure(out2)) return true
        }

        // 3) monkey 兜底
        val out3 = exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>/dev/null")
        return !isLaunchFailure(out3)
    }

    private fun isLaunchFailure(output: String): Boolean {
        return output.contains("Error: Activity not started", true) ||
                output.contains("unable to resolve Intent", true) ||
                output.contains("does not exist", true) ||
                output.contains("Error type 3", true) ||
                output.contains("No activities found", true)
    }

    private fun parseResolveComponent(output: String): String? {
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("/") && !it.startsWith("priority=") && !it.contains(" ") }
    }

    fun openIntent(action: String, data: String? = null) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) append(" -d \"$data\"")
        }
        exec(cmd)
    }

    fun openDeepLink(uri: String) {
        exec("am start -a android.intent.action.VIEW -d \"$uri\"")
    }
}

package com.dougao.app.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 *   1. [DouGaoAccessibilityService] —— 系统无障碍通道：手势、写文字、截图（11+），
 *      **完全不依赖 Shizuku**，中文输入最稳。
 *   2. [ShizukuShell] —— `Shizuku.newProcess` 起独立 shell 进程，**能从 stdout 读二进制**，
 *      截图不再落盘、不再踩权限。
 *   3. AIDL UserService（[ShellService]）—— 兼容旧环境，只在 1/2 不可用时兜底。
 *   4. 本地 `Runtime.exec` —— 无权限时的最后尝试（基本不会成功，仅保证不崩溃）。
 *
 * 到底走哪条通道由用户在「设置 → 高级选项」里选（无障碍模式 / Shizuku 模式）。
 */
class DeviceController(private val context: Context? = null) {

    /**
     * 控制通道偏好（由设置页下发）
     *
     * @param accessibility 用户是否开启了「无障碍模式」
     * @param shizuku      用户是否开启了「Shizuku 模式」
     */
    data class ControlPrefs(
        val accessibility: Boolean = false,
        val shizuku: Boolean = true
    )

    @Volatile
    var controlPrefs: ControlPrefs = ControlPrefs()

    /**
     * 极速模式（豆糕 1.2.1）。
     * 打开后发给模型的截图从 1024 降到 768、JPEG 质量从 80 降到 70 ——
     * 上传体积约少一半，模型要"看"的图块也少四成，每步都能省下几百毫秒到一秒。
     * 由设置页下发，改开关后下一次执行就生效。
     */
    @Volatile
    var speedMode: Boolean = false

    /**
     * Root 模式（豆糕 1.2.2）。
     *
     * 打开后豆糕会用 `su` 直接以 root 身份跑控屏命令，
     * 前提是用户在 Magisk / KernelSU 里给豆糕授过权。由设置页下发。
     */
    @Volatile
    var rootMode: Boolean = false

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

        /** 极速模式下的最大边 */
        private const val MODEL_IMAGE_MAX_DIM_FAST = 768

        /** 极速模式下的 JPEG 质量 */
        private const val MODEL_IMAGE_QUALITY_FAST = 70
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

    // ------------------------------------------------------------------
    // 无障碍通道（豆糕新增）
    // ------------------------------------------------------------------

    /** 无障碍服务是否已连接 */
    fun isAccessibilityConnected(): Boolean = DouGaoAccessibilityService.isConnected()

    /**
     * 无障碍通道能不能截图。
     *
     * `AccessibilityService.takeScreenshot` 是 **Android 11（API 30）** 才有的接口。
     * 更低的版本无障碍只能点按、拿不到画面，而豆糕每一步都要看图才能决策 ——
     * 所以安卓 11 以下「无障碍模式」是不可用的，设置页里会直接置灰。
     *
     * （Shizuku 与 Root 通道走的是 `screencap`，从安卓 4 就有，不受这个限制。）
     */
    fun isAccessibilityCaptureSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** shell 通道是否就绪 */
    fun isShellReady(): Boolean = ShizukuShell.isAvailable() || (serviceBound && shellService != null)

    /** 豆糕自己有没有 root 权限（与 Shizuku 无关） */
    fun isRootAvailable(): Boolean = RootShell.isAvailable()

    /** 重新探测一次 root（用户在管理器里刚授权时调用） */
    fun refreshRootState(): Boolean {
        RootShell.invalidate()
        return RootShell.isAvailable()
    }

    /** Root 模式是否真的能用 */
    private fun isRootReady(): Boolean = rootMode && RootShell.isAvailable()

    private fun a11y(): DouGaoAccessibilityService? = DouGaoAccessibilityService.shared

    /**
     * 这次动作要不要走无障碍通道：
     *  - 用户显式开了「无障碍模式」→ 走无障碍；
     *  - 没显式开，但 Shizuku 通道不可用 → 兜底走无障碍；
     *  - 其余情况走 Shizuku（截图更稳，速度也快）。
     */
    private fun useAccessibility(): Boolean {
        if (a11y() == null) return false
        if (controlPrefs.accessibility) return true
        // 用户明确关掉了 Shizuku 模式 → 只走无障碍
        if (!controlPrefs.shizuku) return true
        return !isShellReady()
    }

    /** 整体是否具备控屏能力（无障碍 / Shizuku / Root 任一可用） */
    fun canControlDevice(): Boolean =
        isAccessibilityConnected() || isShellReady() || isRootReady()

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
     * 执行 shell 并返回文本。
     * 优先 Root（用户显式开了 Root 模式时）→ Shizuku 直连 → AIDL 服务 → 本地。
     */
    private fun exec(command: String): String {
        if (isRootReady()) {
            val r = RootShell.execResult(command)
            if (r.exitCode != RootShell.EXIT_EXCEPTION) return r.combinedText()
        }
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
        if (isRootReady()) {
            val r = RootShell.execResult(command)
            if (r.exitCode != RootShell.EXIT_EXCEPTION) return r.isOk
        }
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
        if (useAccessibility() && a11y()?.tap(x, y) == true) return
        exec("input tap $x $y")
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 800) {
        if (useAccessibility() && a11y()?.longPress(x, y, durationMs) == true) return
        exec("input swipe $x $y $x $y $durationMs")
    }

    fun doubleTap(x: Int, y: Int) {
        if (useAccessibility() && a11y()?.doubleTap(x, y) == true) return
        exec("input tap $x $y")
        try {
            Thread.sleep(90)
        } catch (_: InterruptedException) {
        }
        exec("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 400) {
        if (useAccessibility() && a11y()?.swipe(x1, y1, x2, y2, durationMs) == true) return
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /**
     * 输入文本。
     *
     * 优先顺序：
     *   1. 无障碍 ACTION_SET_TEXT —— 直接往焦点输入框写文字，**中文 / emoji / 特殊符号全支持**；
     *   2. 纯 ASCII 走 `input text`（快）；
     *   3. 含非 ASCII 走「系统剪贴板 + 粘贴键」；
     *   4. 最后兜底内置广播 IME。
     */
    fun type(text: String) {
        if (text.isEmpty()) return

        if (useAccessibility() && a11y()?.inputText(text) == true) return

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
        if (useAccessibility() && a11y()?.pressBack() == true) return
        exec("input keyevent 4")
    }

    fun home() {
        if (useAccessibility() && a11y()?.pressHome() == true) return
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
     *   0. 无障碍 takeScreenshot（Android 11+，不需要 Shizuku）；
     *   1. `Shizuku.newProcess` 直接跑 `screencap -p`，从 stdout 读 PNG 原始字节 —— 最快最稳；
     *   2. 落盘到 App 外部私有目录再读 —— 兼容极老环境；
     *   3. 全部失败返回带原因的黑屏占位图（不再默默假装成功）。
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        var lastError: String? = null

        // 通道 0：无障碍截图（开了无障碍模式 / Shizuku 不可用时优先）
        if (useAccessibility()) {
            captureViaAccessibility()?.let { return@withContext ScreenshotResult(it) }
        }

        // 通道 0.5：Root 直读（开了 Root 模式且拿到 root 时，优先）
        if (isRootReady()) {
            captureViaRoot()?.let { bytes ->
                decodeBitmap(bytes)?.let { return@withContext ScreenshotResult(it) }
            }
        }

        repeat(2) { round ->
            // 通道 1：stdout 直读（主通道）
            captureViaStdout()?.let { bytes ->
                decodeBitmap(bytes)?.let { return@withContext ScreenshotResult(it) }
            }

            // 通道 1.5：无障碍截图补一刀（Shizuku 抽风但无障碍还活着）
            if (!useAccessibility()) {
                captureViaAccessibility()?.let { return@withContext ScreenshotResult(it) }
            }

            // 通道 2：落盘再读（兜底）
            val path = screenshotFallbackPath()
            val fileAttempt = tryCaptureToFile(path)
            fileAttempt?.let { return@withContext ScreenshotResult(it) }
            if (fileAttempt == null) lastError = lastCaptureError ?: lastError

            if (round == 0) delay(120)
        }

        val detail = lastError ?: "截屏通道均不可用（请确认已开启无障碍服务或 Shizuku 已启动并授权）"
        println("[DeviceController] 截图失败：$detail")
        createFallbackScreenshot(isSensitive = false, detail = detail)
    }

    /** 无障碍截图：Android 11+ 才有，返回 null 表示走不通 */
    private suspend fun captureViaAccessibility(): Bitmap? {
        val service = a11y() ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val bmp = service.screenshotBitmap()
            if (bmp == null) {
                lastCaptureError = "无障碍截图被系统拒绝（可能是支付/密码等安全页面）"
            }
            bmp
        } catch (t: Throwable) {
            lastCaptureError = "无障碍截图异常：${t.message}"
            null
        }
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

    /**
     * Root 通道截图：`su -c screencap -p`，stdout 直读 PNG 字节。
     * 和 Shizuku 一样不落盘，所以任何安卓版本都能用（screencap 从安卓 4 就有）。
     */
    private fun captureViaRoot(): ByteArray? {
        val result = RootShell.execResult("screencap -p")
        val err = result.stderrText()

        if (err.contains("Status: -1") || err.contains("Failed", ignoreCase = true)) {
            lastCaptureError = "系统阻止截图（可能是支付/密码页面）"
            return null
        }
        if (result.exitCode == RootShell.EXIT_NO_ROOT) {
            lastCaptureError = "豆糕还没有拿到 root 权限"
            return null
        }
        if (result.exitCode != 0) {
            lastCaptureError = "root screencap 执行失败（exit=${result.exitCode}）${if (err.isNotBlank()) " · ${err.take(120)}" else ""}"
            return null
        }

        val bytes = result.stdout
        if (bytes.isEmpty()) {
            lastCaptureError = "root screencap 没有输出数据"
            return null
        }
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte()) {
            lastCaptureError = "root screencap 输出不是合法图片"
            return null
        }
        lastCaptureError = null
        return bytes
    }

    private fun screenshotFallbackPath(): String {        val dir = appExternalDir
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
     * 极速模式下进一步压到 768 / 质量 70。
     */
    fun toModelBase64(bitmap: Bitmap): String {
        val maxDimLimit = if (speedMode) MODEL_IMAGE_MAX_DIM_FAST else MODEL_IMAGE_MAX_DIM
        val quality = if (speedMode) MODEL_IMAGE_QUALITY_FAST else 80

        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxDim > maxDimLimit) {
            val scale = maxDimLimit.toFloat() / maxDim
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
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------
    // 屏幕信息
    // ------------------------------------------------------------------

    fun getScreenSize(): Pair<Int, Int> {
        // 无障碍通道能直接拿到真实分辨率，省掉一次 shell 往返
        a11y()?.let {
            val size = it.screenSize()
            if (size.first > 0 && size.second > 0) return size
        }

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
        // 无障碍通道：直接读当前窗口的包名，最快
        a11y()?.currentPackageName()?.let { return it }
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

        // 0) 没有 shell 通道（比如只开了无障碍模式）→ 直接用系统 API 拉起。
        //    豆糕执行前必须授予悬浮窗权限，因此不受「后台启动 Activity」限制。
        if (!isShellReady()) {
            if (launchViaPackageManager(packageName)) return true
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
        if (!isLaunchFailure(out3)) return true

        // 4) 最后再试一次系统 API
        return launchViaPackageManager(packageName)
    }

    /** 用 PackageManager 直接拉起应用（不需要任何 shell 权限） */
    private fun launchViaPackageManager(packageName: String): Boolean {
        val ctx: Context =
            context ?: runCatching { App.getInstance() }.getOrNull() ?: return false
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
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

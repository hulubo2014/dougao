package com.dougao.app.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 豆糕无障碍服务（豆糕新增能力）
 *
 * ============ 它解决什么问题 ============
 *
 * 原来豆糕控屏只能靠 Shizuku（或 root）跑 `input tap` / `screencap`。
 * 问题是：不是每个手机都愿意折腾 Shizuku，而安卓系统自带的「无障碍服务」本来
 * 就是官方给自动化留的正门 —— 只要用户手动授权一次，就能：
 *
 *   • 派发手势        dispatchGesture          → 点击 / 滑动 / 长按 / 双击
 *   • 系统按键        performGlobalAction      → 返回 / 主页 / 多任务
 *   • 直接写文字      ACTION_SET_TEXT          → **中文、emoji 都能输入**（`input text` 做不到）
 *   • 截图（11+）      takeScreenshot           → 不用 screencap，不落盘
 *   • 读界面节点      rootInActiveWindow       → 拿当前包名、找输入框
 *
 * 参考借鉴：zai-org/Open-AutoGLM 与 AndroidAutoGLM 都保留了一条无障碍通道，
 * 这里按豆糕的动作集合补齐实现。
 *
 * ============ 重要说明 ============
 * 这个服务**只在用户主动下达任务时被调用**，不会监听、上报、记录任何内容，
 * 也没有做任何后台自动操作。[onAccessibilityEvent] 是空实现。
 */
class DouGaoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DouGaoA11y"

        @Volatile
        private var instance: DouGaoAccessibilityService? = null

        /** 服务实例（未连接时为 null） */
        val shared: DouGaoAccessibilityService? get() = instance

        /** 系统里是否已连接（进程内实例存在） */
        fun isConnected(): Boolean = instance != null

        /**
         * 系统设置里是否已勾选「豆糕」。
         * 比 isConnected() 宽松：刚勾选还没连上时也能立刻反馈给 UI。
         */
        fun isEnabled(context: Context): Boolean {
            return try {
                val expected = ComponentName(context, DouGaoAccessibilityService::class.java)
                val raw = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                raw.split(':').any {
                    val item = it.trim()
                    item.equals(expected.flattenToString(), true) ||
                            item.equals(expected.flattenToShortString(), true)
                }
            } catch (t: Throwable) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 空实现：豆糕不监听、不上报任何界面事件 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ==================================================================
    // 手势
    // ==================================================================

    /** 单击 */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, 0L, 60L)
    }

    /** 长按 */
    fun longPress(x: Int, y: Int, durationMs: Int = 800): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, 0L, durationMs.coerceIn(200, 3000).toLong())
    }

    /** 滑动 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 400): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, 0L, durationMs.coerceIn(60, 3000).toLong())
    }

    /** 双击（两次短按，间隔 90ms） */
    fun doubleTap(x: Int, y: Int): Boolean {
        if (!tap(x, y)) return false
        try {
            Thread.sleep(90)
        } catch (_: InterruptedException) {
        }
        return tap(x, y)
    }

    /**
     * 派发一个手势笔画。
     *
     * 注意：dispatchGesture 在「上一个手势还没结束」时会返回 false，
     * 所以这里用 GestureResultCallback + CountDownLatch 等到它真正完成，
     * 避免连续动作被系统丢掉（这是无障碍自动化最常见的坑）。
     */
    private fun dispatch(path: Path, startTime: Long, duration: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) {
            // 主线程不能阻塞，直接派发
            return try {
                dispatchGesture(gesture, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "派发手势失败：${t.message}")
                false
            }
        }

        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed = false
                latch.countDown()
            }
        }

        return try {
            val dispatched = dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
            if (!dispatched) return false
            latch.await((duration + 1500).coerceAtMost(5000L), TimeUnit.MILLISECONDS)
            completed
        } catch (t: Throwable) {
            Log.w(TAG, "派发手势异常：${t.message}")
            false
        }
    }

    // ==================================================================
    // 系统按键
    // ==================================================================

    fun pressBack(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)

    fun pressHome(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)

    fun pressRecents(): Boolean = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)

    // ==================================================================
    // 文字输入
    // ==================================================================

    /**
     * 直接把文字写进当前焦点输入框。
     *
     * 这是无障碍通道最值钱的一条：`input text` 对中文几乎必挂，
     * 而 ACTION_SET_TEXT 是系统级写文本，中文 / emoji / 特殊符号全都能进。
     */
    fun inputText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow
            var target = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null || !target.isEditable) {
                target = root?.let { findFirstEditable(it) }
            }
            if (target == null) return false

            if (!target.isFocused) {
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "输入文字 ok=$ok len=${text.length}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "输入文字失败：${t.message}")
            false
        }
    }

    /** 在节点树里找第一个可编辑、可用的输入框 */
    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ==================================================================
    // 截图（Android 11+）
    // ==================================================================

    /**
     * 无障碍截图。只有 Android 11(API 30) 及以上才支持，
     * 返回 null 表示不支持 / 被安全策略拦截（支付、密码页）。
     */
    suspend fun screenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = try {
                                val buffer = result.hardwareBuffer
                                val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                buffer.close()
                                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (t: Throwable) {
                                Log.w(TAG, "解析截图失败：${t.message}")
                                null
                            }
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "无障碍截图失败，errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "无障碍截图异常：${t.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ==================================================================
    // 屏幕 / 前台应用信息
    // ==================================================================

    /** 屏幕真实分辨率（跟随旋转） */
    fun screenSize(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (t: Throwable) {
            Pair(1080, 2400)
        }
    }

    /** 当前前台应用包名 */
    fun currentPackageName(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()?.takeIf { it.contains(".") }
        } catch (t: Throwable) {
            null
        }
    }
}

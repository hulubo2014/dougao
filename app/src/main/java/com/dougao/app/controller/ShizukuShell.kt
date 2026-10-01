package com.dougao.app.controller

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 统一 Shell 底座（豆糕核心组件）
 *
 * 为什么要有这个类：
 * 旧实现只能通过自定义 AIDL 服务跑 shell，返回类型是 String —— 而 `screencap -p`
 * 输出的是**二进制 PNG**，走 String 必然损坏；于是只好退化成"先落盘再读文件"，
 * 而落盘位置又踩权限（/data/local/tmp 普通 App 读不到），最终结果就是**截图必黑**。
 *
 * 参考 zai-org/Open-AutoGLM 的 Android 实现（AndroidAutoGLM / Aries-AI）后改为：
 * 用 `Shizuku.newProcess("sh", "-c", cmd)` 起一个独立 shell 进程，
 * **直接从 stdout 读原始字节**。零落盘、零文件权限、零 SELinux 拦截。
 *
 * 这是目前最稳、最快的一条路。
 */
object ShizukuShell {

    private const val TAG = "DouGaoShell"

    /** exitCode 约定 */
    const val EXIT_NO_BINDER = -1
    const val EXIT_NO_PERMISSION = -2
    const val EXIT_EXCEPTION = -3

    data class ExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray
    ) {
        val isOk: Boolean get() = exitCode == 0

        fun stdoutText(): String = try {
            String(stdout, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }

        fun stderrText(): String = try {
            String(stderr, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }

        /** stdout + stderr，方便把报错原文展示出来 */
        fun combinedText(): String {
            val out = stdoutText()
            val err = stderrText()
            return if (err.isBlank()) out else "$out\n$err"
        }
    }

    /** Shizuku 进程是否可用（binder 活着 + 已授权） */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 反射调用 Shizuku.newProcess。
     * 不同 Shizuku 版本可见性不同，先试公开方法，不行再试私有（设 accessible）。
     */
    private fun newProcess(args: Array<String>): Any {
        val method = try {
            Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
        } catch (_: NoSuchMethodException) {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }
        return method.invoke(null, args, null, null) as Any
    }

    /**
     * 执行 shell 命令，返回原始字节。
     *
     * @param command 例如 "screencap -p"、"input tap 100 200"
     * @param binary  true 表示输出可能含二进制（截图），日志里不做文本处理
     */
    fun execResult(command: String, binary: Boolean = false): ExecResult {
        if (!isAvailable()) {
            val granted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
            return ExecResult(
                if (granted) EXIT_NO_BINDER else EXIT_NO_PERMISSION,
                ByteArray(0),
                ByteArray(0)
            )
        }

        return try {
            val process = newProcess(arrayOf("sh", "-c", command))
            val cls = process.javaClass

            val stdout = cls.getMethod("getInputStream").invoke(process) as InputStream
            val stderr = cls.getMethod("getErrorStream").invoke(process) as InputStream

            // 注意顺序：先读完 stdout 再 waitFor，避免管道写满导致死锁
            val outBytes = readAll(stdout)
            val errBytes = readAll(stderr)
            val exitCode = cls.getMethod("waitFor").invoke(process) as Int

            if (Binary.isStderrText(errBytes) && !binary) {
                if (exitCode != 0) {
                    Log.w(TAG, "shell exit=$exitCode cmd=$command err=${String(errBytes, Charsets.UTF_8).take(200)}")
                }
            }
            ExecResult(exitCode, outBytes, errBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "shell 执行异常 cmd=$command : ${t.message}")
            ExecResult(EXIT_EXCEPTION, ByteArray(0), ByteArray(0))
        }
    }

    /** 只要 stdout 原始字节；失败返回 null */
    fun execBytes(command: String): ByteArray? {
        val r = execResult(command, binary = true)
        return if (r.isOk && r.stdout.isNotEmpty()) r.stdout else null
    }

    /** 执行并返回文本输出（stdout + stderr） */
    fun execText(command: String): String = execResult(command).combinedText()

    /** 执行，只关心成败 */
    fun execOk(command: String): Boolean = execResult(command).isOk

    private fun readAll(input: InputStream): ByteArray {
        return try {
            input.use { ins ->
                ByteArrayOutputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    private object Binary {
        fun isStderrText(bytes: ByteArray): Boolean = bytes.isNotEmpty()
    }
}

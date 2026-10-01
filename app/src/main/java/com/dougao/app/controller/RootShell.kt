package com.dougao.app.controller

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Root 通道（豆糕 1.2.2 新增）
 *
 * 语义：用户在 **Magisk / KernelSU 之类的 root 管理器里给「豆糕」授权**之后，
 * 豆糕自己就拿到了 root 身份，可以直接跑 `input tap` / `screencap -p` 控屏，
 * **不需要 Shizuku、也不需要无障碍**。
 *
 * 实现：一次性 `su -c "<cmd>"` 进程，stdout 读原始字节。
 * 跟 [ShizukuShell] 一样是"零落盘"，所以截图不会踩 SELinux 权限。
 */
object RootShell {

    private const val TAG = "DouGaoRoot"

    /** exitCode 约定 */
    const val EXIT_NO_ROOT = -1
    const val EXIT_EXCEPTION = -3

    /** root 探测结果缓存多久（授权状态可能随时变，别缓存太久） */
    private const val CHECK_TTL_MS = 8_000L

    @Volatile
    private var cachedAvailable: Boolean? = null

    @Volatile
    private var lastCheckAt = 0L

    /** 强制下次重新探测（用户刚在管理器里改了授权时调用） */
    fun invalidate() {
        cachedAvailable = null
        lastCheckAt = 0L
    }

    /** 豆糕现在有没有 root 权限 */
    fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedAvailable
        if (cached != null && now - lastCheckAt < CHECK_TTL_MS) return cached
        val ok = probe()
        cachedAvailable = ok
        lastCheckAt = now
        return ok
    }

    /** 真去跑一次 `su -c id`，看回来的是不是 uid=0 */
    private fun probe(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val out = readAll(process.inputStream)
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                String(out, Charsets.UTF_8).contains("uid=0")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "root 探测失败：${t.message}")
            false
        }
    }

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

        fun combinedText(): String {
            val out = stdoutText()
            val err = stderrText()
            return if (err.isBlank()) out else "$out\n$err"
        }
    }

    /**
     * 以 root 身份执行一条命令。
     *
     * @param command 例如 "screencap -p"、"input tap 100 200"
     */
    fun execResult(command: String): ExecResult {
        if (!isAvailable()) {
            return ExecResult(EXIT_NO_ROOT, ByteArray(0), ByteArray(0))
        }
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            // 先读完 stdout 再 waitFor，避免管道写满死锁
            val outBytes = readAll(process.inputStream)
            val errBytes = readAll(process.errorStream)
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return ExecResult(EXIT_EXCEPTION, outBytes, errBytes)
            }
            ExecResult(process.exitValue(), outBytes, errBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "root 执行异常 cmd=$command : ${t.message}")
            ExecResult(EXIT_EXCEPTION, ByteArray(0), ByteArray(0))
        }
    }

    /** 只要 stdout 原始字节；失败返回 null */
    fun execBytes(command: String): ByteArray? {
        val r = execResult(command)
        return if (r.isOk && r.stdout.isNotEmpty()) r.stdout else null
    }

    /** 执行并返回文本（stdout + stderr） */
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
}

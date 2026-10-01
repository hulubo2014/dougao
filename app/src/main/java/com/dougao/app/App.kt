package com.dougao.app

import android.app.Application
import android.content.pm.PackageManager
import com.dougao.app.controller.AppScanner
import com.dougao.app.controller.DeviceController
import com.dougao.app.data.SettingsManager
import com.dougao.app.skills.SkillManager
import com.dougao.app.tools.ToolManager
import com.dougao.app.utils.CrashHandler
import rikka.shizuku.Shizuku

class App : Application() {

    lateinit var deviceController: DeviceController
        private set
    lateinit var appScanner: AppScanner
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化崩溃捕获（仅本地日志，不联网上报）
        CrashHandler.getInstance().init(this)

        // 初始化 Shizuku
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)

        // 初始化核心组件
        initializeComponents()
    }

    private fun initializeComponents() {
        // 初始化设备控制器
        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)

        // 初始化应用扫描器
        appScanner = AppScanner(this)

        // 初始化 Tools 层
        val toolManager = ToolManager.init(this, deviceController, appScanner)

        // 异步预扫描应用列表（避免 ANR）
        println("[App] 开始异步扫描已安装应用...")
        Thread {
            appScanner.refreshApps()
            println("[App] 已扫描 ${appScanner.getApps().size} 个应用")
        }.start()

        // 初始化 Skills 层（传入 appScanner 用于检测已安装应用）
        val skillManager = SkillManager.init(this, toolManager, appScanner)
        println("[App] SkillManager 已加载 ${skillManager.getAllSkills().size} 个 Skills")

        println("[App] 组件初始化完成")
    }

    override fun onTerminate() {
        super.onTerminate()
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    /**
     * 崩溃处理：**只写本地日志，永不上报**（云端上报已在豆糕 1.2.0 移除）
     */
    fun onCrashLogOnly() {
        println("[App] 崩溃日志仅保存在本地")
    }

    companion object {
        @Volatile
        private var instance: App? = null

        fun getInstance(): App {
            return instance ?: throw IllegalStateException("App 未初始化")
        }

        private val REQUEST_PERMISSION_RESULT_LISTENER =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                println("[Shizuku] Permission result: $granted")
            }
    }
}

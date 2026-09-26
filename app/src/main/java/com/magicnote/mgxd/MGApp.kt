package com.magicnote.mgxd

import android.app.Application
import android.os.StrictMode
import com.magicnote.mgxd.data.db.AppDatabase
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.data.repo.AppRepository

/**
 * Magic Note - 应用入口
 * 负责初始化数据库、偏好设置与仓库（简易依赖注入容器）
 */
class MGApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * 记录未捕获异常到 /sdcard/Android/data/<包名>/files/crash-last.txt
     * （应用自有目录，无需存储权限；闪退后可把这个文件发给开发者定位）
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = java.io.File(dir, "crash-last.txt")
                val time = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                out.writeText(
                    "time=" + time + "\n" +
                        "version=" + BuildConfig.VERSION_NAME + "\n" +
                        "thread=" + thread.name + "\n" +
                        android.util.Log.getStackTraceString(e)
                )
            } catch (ignore: Throwable) {
                // 记录失败则忽略，不影响正常崩溃流程
            }
            previous?.uncaughtException(thread, e)
        }
    }

    override fun onCreate() {
        // 崩溃自诊断：把未捕获异常堆栈写到外部私有目录，便于定位发行版闪退
        installCrashLogger()
        // 调试构建开启 StrictMode：把主线程磁盘/网络读写、未关闭资源直接打到 Logcat
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 简易服务定位容器：避免引入 Hilt，保持轻量
 */
class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.getInstance(app) }
    val userPrefs: UserPrefs by lazy { UserPrefs(app) }
    val repository: AppRepository by lazy { AppRepository(database, userPrefs) }
}
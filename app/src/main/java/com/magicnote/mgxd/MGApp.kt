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
     * 崩溃自诊断：把未捕获异常写到手机的 Download/MagicNote-crash/ 专门文件夹
     * （文件名 crash-yyyyMMdd-HHmmss.txt，一眼能找到，方便用户发回给开发者）
     * 写入优先级：
     *   1) Android 10+ 用 MediaStore 写 Download（无需任何存储权限）
     *   2) 旧系统直接写公共 Download（Manifest 已申请 WRITE_EXTERNAL_STORAGE，maxSdkVersion=28）
     *   3) 都失败则回退到应用外部私有目录 crash-last.txt
     * 记录失败一律忽略，绝不影响正常崩溃流程。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val time = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val text = "time=" + time + "\n" +
                "version=" + BuildConfig.VERSION_NAME + "\n" +
                "package=" + packageName + "\n" +
                "thread=" + thread.name + "\n" +
                android.util.Log.getStackTraceString(e)
            saveCrashLog(text)
            previous?.uncaughtException(thread, e)
        }
    }

    /** 把崩溃文本写到 Download/MagicNote-crash/（见 installCrashLogger 的优先级说明） */
    private fun saveCrashLog(text: String) {
        val fileName = "crash-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".txt"

        // 1) Android 10+：MediaStore（无需权限），RELATIVE_PATH 直接在 Download 下建子文件夹
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/" + CRASH_DIR
                    )
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    return
                }
            }
        } catch (ignore: Throwable) {
        }

        // 2) 旧系统（或 MediaStore 失败）：直接写公共 Download/MagicNote-crash/
        try {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val dir = java.io.File(downloads, CRASH_DIR)
            if (dir.exists() || dir.mkdirs()) {
                java.io.File(dir, fileName).writeText(text)
                return
            }
        } catch (ignore: Throwable) {
        }

        // 3) 最终兜底：应用外部私有目录
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.File(dir, "crash-last.txt").writeText(text)
        } catch (ignore: Throwable) {
        }
    }

    companion object {
        /** 崩溃日志在 Download 下的专门文件夹（便于用户查找与反馈） */
        private const val CRASH_DIR = "MagicNote-crash"
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
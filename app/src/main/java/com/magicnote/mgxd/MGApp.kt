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

    override fun onCreate() {
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
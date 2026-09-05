package com.magicnote.mgxd

import android.app.Application
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
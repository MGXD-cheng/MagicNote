package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magicnote.mgxd.ai.AiClient
import java.net.URL
import java.net.HttpURLConnection
import com.magicnote.mgxd.update.UpdateInfo
import com.magicnote.mgxd.update.UpdateChecker
import com.magicnote.mgxd.lan.LanSyncServer
import com.magicnote.mgxd.util.DiaryLock
import com.magicnote.mgxd.data.prefs.UserPrefs
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.ReminderScheduler
import com.magicnote.mgxd.screentime.ScreenTimeManager
import com.magicnote.mgxd.screentime.ScreenTimeMonitor
import com.magicnote.mgxd.service.KeepAliveService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val repo: AppRepository) : ViewModel() {

    private val _aiConfig = MutableStateFlow(UserPrefs.AiConfig())
    val aiConfig: StateFlow<UserPrefs.AiConfig> = _aiConfig.asStateFlow()

    private val _notifyConfig = MutableStateFlow(UserPrefs.NotifyConfig())
    val notifyConfig: StateFlow<UserPrefs.NotifyConfig> = _notifyConfig.asStateFlow()

    private val _screenTimeConfig = MutableStateFlow(UserPrefs.ScreenTimeConfig())
    val screenTimeConfig: StateFlow<UserPrefs.ScreenTimeConfig> = _screenTimeConfig.asStateFlow()

    /** 今日屏幕时间统计 */
    data class ScreenTimeStats(
        val todayUsageMillis: Long = 0L,
        val apps: List<ScreenTimeManager.AppUsage> = emptyList()
    )

    private val _screenTimeStats = MutableStateFlow<ScreenTimeStats?>(null)
    val screenTimeStats: StateFlow<ScreenTimeStats?> = _screenTimeStats.asStateFlow()

    private val _categoryOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val categoryOverrides: StateFlow<Map<String, String>> = _categoryOverrides.asStateFlow()

    private val _pureMode = MutableStateFlow(false)
    val pureMode: StateFlow<Boolean> = _pureMode.asStateFlow()

    private val _moduleConfig = MutableStateFlow(UserPrefs.ModuleConfig())
    val moduleConfig: StateFlow<UserPrefs.ModuleConfig> = _moduleConfig.asStateFlow()

    private val _diaryAutoReply = MutableStateFlow(false)
    val diaryAutoReply: StateFlow<Boolean> = _diaryAutoReply.asStateFlow()

    private val _modelVision = MutableStateFlow(false)
    val modelVision: StateFlow<Boolean> = _modelVision.asStateFlow()

    /** Magic AI 输出自动渲染 Markdown */
    private val _markdownRender = MutableStateFlow(true)
    val markdownRender: StateFlow<Boolean> = _markdownRender.asStateFlow()

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    // ---------- 日记锁 ----------
    private val _diaryLockEnabled = MutableStateFlow(false)
    val diaryLockEnabled: StateFlow<Boolean> = _diaryLockEnabled.asStateFlow()
    private val _diaryLockMode = MutableStateFlow("password")
    val diaryLockMode: StateFlow<String> = _diaryLockMode.asStateFlow()
    private val _diaryLockHasPassword = MutableStateFlow(false)
    val diaryLockHasPassword: StateFlow<Boolean> = _diaryLockHasPassword.asStateFlow()

    // ---------- 检查更新 ----------
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()
    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()
    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError.asStateFlow()
    private val _updateDownloading = MutableStateFlow(false)
    val updateDownloading: StateFlow<Boolean> = _updateDownloading.asStateFlow()
    private val _updateProgress = MutableStateFlow(0)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    // ---------- 局域网同步（预览 / 两台设备传数据） ----------
    private var lanServer: LanSyncServer? = null
    private val _lanUrl = MutableStateFlow<String?>(null)
    val lanUrl: StateFlow<String?> = _lanUrl.asStateFlow()
    private val _lanImportText = MutableStateFlow<String?>(null)
    val lanImportText: StateFlow<String?> = _lanImportText.asStateFlow()
    private val _lanDownloading = MutableStateFlow(false)
    val lanDownloading: StateFlow<Boolean> = _lanDownloading.asStateFlow()
    private val _lanError = MutableStateFlow<String?>(null)
    val lanError: StateFlow<String?> = _lanError.asStateFlow()

    // ---------- 模型列表（按当前 Base URL 自动拉取） ----------
    private val _modelList = MutableStateFlow<List<String>?>(null)
    val modelList: StateFlow<List<String>?> = _modelList.asStateFlow()
    private val _modelListLoading = MutableStateFlow(false)
    val modelListLoading: StateFlow<Boolean> = _modelListLoading.asStateFlow()
    private val _modelListError = MutableStateFlow<String?>(null)
    val modelListError: StateFlow<String?> = _modelListError.asStateFlow()

    init {
        viewModelScope.launch { repo.aiConfig.collect { _aiConfig.value = it } }
        viewModelScope.launch { repo.diaryLockEnabled.collect { _diaryLockEnabled.value = it } }
        viewModelScope.launch { repo.diaryLockMode.collect { _diaryLockMode.value = it } }
        viewModelScope.launch {
            repo.diaryLockHash.collect { _diaryLockHasPassword.value = !it.isNullOrBlank() }
        }
        viewModelScope.launch { repo.notifyConfig.collect { _notifyConfig.value = it } }
        viewModelScope.launch { repo.screenTimeConfig.collect { _screenTimeConfig.value = it } }
        viewModelScope.launch { repo.categoryOverrides.collect { _categoryOverrides.value = it } }
        viewModelScope.launch { repo.pureMode.collect { _pureMode.value = it } }
        viewModelScope.launch { repo.moduleConfig.collect { _moduleConfig.value = it } }
        viewModelScope.launch { repo.diaryAutoReply.collect { _diaryAutoReply.value = it } }
        viewModelScope.launch { repo.modelVision.collect { _modelVision.value = it } }
        viewModelScope.launch { repo.markdownRender.collect { _markdownRender.value = it } }
        viewModelScope.launch { repo.themeMode.collect { _themeMode.value = it } }
    }

    /** 刷新今日屏幕时间统计（IO 查询，需使用情况访问权限） */
    fun refreshScreenTimeStats(context: Context) {
        viewModelScope.launch {
            _screenTimeStats.value = withContext(Dispatchers.IO) {
                if (!ScreenTimeManager.hasUsageAccess(context)) {
                    ScreenTimeStats()
                } else {
                    ScreenTimeStats(
                        todayUsageMillis = ScreenTimeManager.getTodayUsageMillis(context),
                        apps = ScreenTimeManager.getTodayAppUsages(context, limit = 8)
                    )
                }
            }
        }
    }

    /** 保存屏幕时间配置并同步调度/取消监控 */
    fun saveScreenTimeConfig(context: Context, enabled: Boolean, thresholdMinutes: Int) {
        viewModelScope.launch {
            repo.saveScreenTimeConfig(enabled, thresholdMinutes)
            if (enabled) {
                ScreenTimeMonitor.scheduleCheck(context)
            } else {
                ScreenTimeMonitor.cancelCheck(context)
            }
        }
    }

    /** 设置应用自定义分类 */
    fun saveCategoryOverride(pkg: String, category: String) {
        viewModelScope.launch { repo.saveCategoryOverride(pkg, category) }
    }

    /** 删除应用自定义分类 */
    fun removeCategoryOverride(pkg: String) {
        viewModelScope.launch { repo.removeCategoryOverride(pkg) }
    }

    fun saveAiConfig(
        baseUrl: String, apiKey: String, model: String,
        personalityId: String, customPrompt: String
    ) {
        viewModelScope.launch {
            repo.saveAiConfig(baseUrl.trim(), apiKey.trim(), model.trim(), personalityId, customPrompt)
        }
    }

    fun saveNotifyConfig(
        context: Context,
        dailySummaryEnabled: Boolean,
        hour: Int,
        minute: Int,
        notificationEnabled: Boolean
    ) {
        viewModelScope.launch {
            repo.saveNotifyConfig(dailySummaryEnabled, hour, minute, notificationEnabled)
            if (notificationEnabled && dailySummaryEnabled) {
                ReminderScheduler.scheduleDailySummary(context, hour, minute)
            } else {
                ReminderScheduler.cancelDailySummary(context)
            }
        }
    }

    /** 纯净模式开关：开启后关闭后台保活与所有后台功能，关闭后恢复 */
    fun setPureMode(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            repo.savePureMode(enabled)
            if (enabled) {
                // 关闭所有后台：停止保活服务 + 取消全部闹钟/监控
                KeepAliveService.stop(context)
                ReminderScheduler.cancelAll(context, repo)
            } else {
                // 恢复所有后台：重排全部闹钟 + 启动保活服务
                ReminderScheduler.rescheduleAll(context, repo)
                KeepAliveService.start(context)
            }
        }
    }

    /** 功能模块开关：关闭后底部导航与首页对应入口/卡片隐藏 */
    fun saveModuleConfig(todoEnabled: Boolean, calendarEnabled: Boolean, diaryEnabled: Boolean) {
        viewModelScope.launch {
            repo.saveModuleConfig(todoEnabled, calendarEnabled, diaryEnabled)
        }
    }

    /** 日记自动回复开关：开启后每次写完日记 AI 自动回复 */
    fun saveDiaryAutoReply(enabled: Boolean) {
        viewModelScope.launch {
            repo.saveDiaryAutoReply(enabled)
        }
    }

    /** 模型支持图片识别开关：开启后 AI 注入日记时连同图片一起识别 */
    fun saveModelVision(enabled: Boolean) {
        viewModelScope.launch {
            repo.saveModelVision(enabled)
        }
    }

    /** Magic AI 输出自动渲染 Markdown 开关 */
    fun saveMarkdownRender(enabled: Boolean) {
        viewModelScope.launch {
            repo.saveMarkdownRender(enabled)
        }
    }
    /** 外观主题：system=跟随系统 / light=浅色 / dark=深色 */
    fun setThemeMode(mode: String) {
        viewModelScope.launch { repo.saveThemeMode(mode) }
    }

    /** 按当前 Base URL 拉取该站点可用模型列表（结果通过 modelList 弹窗选择） */
    fun fetchModelList(baseUrl: String, apiKey: String) {
        if (_modelListLoading.value) return
        viewModelScope.launch {
            _modelListLoading.value = true
            _modelListError.value = null
            try {
                val list = AiClient().fetchModels(baseUrl, apiKey)
                if (list.isEmpty()) {
                    _modelListError.value = "未获取到模型列表，请检查 Base URL"
                } else {
                    _modelList.value = list
                }
            } catch (e: Exception) {
                _modelListError.value = e.message ?: "获取模型列表失败"
            } finally {
                _modelListLoading.value = false
            }
        }
    }

    fun clearModelList() { _modelList.value = null }
    fun clearModelListError() { _modelListError.value = null }

    // ==================== 检查更新 ====================
    fun checkUpdate(context: Context) {
        if (_updateChecking.value) return
        viewModelScope.launch {
            _updateChecking.value = true
            _updateError.value = null
            _updateMessage.value = null
            try {
                val current = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "0"
                val info = UpdateChecker.check(current)
                if (info == null) {
                    _updateMessage.value = "已是最新版本 v" + current
                } else {
                    _updateInfo.value = info
                }
            } catch (e: Exception) {
                _updateError.value = "检查更新失败：" + (e.message ?: "网络异常")
            } finally {
                _updateChecking.value = false
            }
        }
    }

    fun downloadAndInstallUpdate(context: Context) {
        val info = _updateInfo.value ?: return
        val url = info.apkUrl ?: return
        if (_updateDownloading.value) return
        viewModelScope.launch {
            _updateDownloading.value = true
            _updateProgress.value = 0
            try {
                val file = UpdateChecker.downloadApk(context, url, info.latestVersion) { p ->
                    _updateProgress.value = p
                }
                UpdateChecker.install(context, file)
                _updateInfo.value = null
            } catch (e: Exception) {
                _updateError.value = "下载失败：" + (e.message ?: "网络异常")
            } finally {
                _updateDownloading.value = false
            }
        }
    }

    fun openReleasePage(context: Context) {
        val url = _updateInfo.value?.htmlUrl ?: return
        UpdateChecker.openReleasePage(context, url)
    }

    fun dismissUpdate() { _updateInfo.value = null }
    fun clearUpdateMessage() { _updateMessage.value = null }
    fun clearUpdateError() { _updateError.value = null }

    // ==================== 局域网同步 ====================
    fun startLanSync(
        exportProvider: suspend () -> String,
        summaryProvider: suspend () -> String,
        apkProvider: (() -> com.magicnote.mgxd.lan.LanApk?)? = null
    ) {
        if (lanServer?.isRunning == true) return
        val server = LanSyncServer(
            exportProvider = exportProvider,
            summaryProvider = summaryProvider,
            apkProvider = apkProvider
        )
        server.start()
            .onSuccess { url -> lanServer = server; _lanUrl.value = url }
            .onFailure { e -> _lanError.value = "局域网服务启动失败：" + (e.message ?: "端口被占用") }
    }

    fun stopLanSync() {
        lanServer?.stop()
        lanServer = null
        _lanUrl.value = null
    }

    /** 从另一台设备下载 .mgxd 文本（成功后 lanImportText 非空，由 UI 触发导入） */
    fun downloadFromLan(address: String) {
        if (_lanDownloading.value) return
        viewModelScope.launch {
            _lanDownloading.value = true
            _lanError.value = null
            try {
                val text = withContext(Dispatchers.IO) {
                    val base = address.trim().let { if (it.startsWith("http")) it else "http://" + it }.trimEnd('/')
                    val target = if (base.endsWith(".mgxd")) base else base + "/export.mgxd"
                    val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 30000
                        requestMethod = "GET"
                    }
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                if (text.isBlank()) {
                    _lanError.value = "对方未返回数据，请确认已开启局域网同步"
                } else {
                    _lanImportText.value = text
                }
            } catch (e: Exception) {
                _lanError.value = "从局域网下载失败：" + (e.message ?: "无法连接")
            } finally {
                _lanDownloading.value = false
            }
        }
    }

    fun consumeLanImport() { _lanImportText.value = null }
    fun clearLanError() { _lanError.value = null }

    // ==================== 日记锁 ====================
    fun setDiaryLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.saveDiaryLock(enabled, _diaryLockMode.value) }
    }

    fun setDiaryLockMode(mode: String) {
        viewModelScope.launch { repo.saveDiaryLock(_diaryLockEnabled.value, mode) }
    }

    /** 设置 / 修改数字密码（自动生成新盐；同时开启日记锁） */
    fun saveDiaryPassword(newPassword: String) {
        viewModelScope.launch {
            val salt = DiaryLock.newSalt()
            repo.saveDiaryLock(true, _diaryLockMode.value, DiaryLock.hash(newPassword, salt), salt)
        }
    }

    /** 校验当前密码（修改密码 / 关闭锁时使用） */
    suspend fun verifyDiaryPassword(input: String): Boolean {
        val salt = repo.diaryLockSalt.first() ?: return false
        val hash = repo.diaryLockHash.first() ?: return false
        return DiaryLock.hash(input, salt) == hash
    }

    override fun onCleared() {
        lanServer?.stop()
        super.onCleared()
    }
}
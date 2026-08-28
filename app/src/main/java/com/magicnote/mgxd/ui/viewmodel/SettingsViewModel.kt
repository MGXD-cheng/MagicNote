package com.magicnote.mgxd.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    init {
        viewModelScope.launch { repo.aiConfig.collect { _aiConfig.value = it } }
        viewModelScope.launch { repo.notifyConfig.collect { _notifyConfig.value = it } }
        viewModelScope.launch { repo.screenTimeConfig.collect { _screenTimeConfig.value = it } }
        viewModelScope.launch { repo.categoryOverrides.collect { _categoryOverrides.value = it } }
        viewModelScope.launch { repo.pureMode.collect { _pureMode.value = it } }
        viewModelScope.launch { repo.moduleConfig.collect { _moduleConfig.value = it } }
        viewModelScope.launch { repo.diaryAutoReply.collect { _diaryAutoReply.value = it } }
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
}
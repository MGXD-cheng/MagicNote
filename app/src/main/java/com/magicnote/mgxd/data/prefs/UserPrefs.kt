package com.magicnote.mgxd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/**
 * 用户偏好设置（DataStore）
 * 存放 AI 配置、性格设置、通知设置等
 */
class UserPrefs(private val context: Context) {

    companion object {
        private val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_PERSONALITY_ID = stringPreferencesKey("personality_id")
        private val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        private val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        private val KEY_DAILY_SUMMARY_HOUR = intPreferencesKey("daily_summary_hour")
        private val KEY_DAILY_SUMMARY_MINUTE = intPreferencesKey("daily_summary_minute")
        private val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
        private val KEY_SCREEN_TIME_ENABLED = booleanPreferencesKey("screen_time_enabled")
        private val KEY_SCREEN_TIME_THRESHOLD = intPreferencesKey("screen_time_threshold_min")
        private val KEY_SCREEN_TIME_WARN_PKG = stringPreferencesKey("screen_time_warn_pkg")
        private val KEY_SCREEN_TIME_WARN_AT = longPreferencesKey("screen_time_warn_at")
        private val KEY_AI_TODO_REMIND_AT = longPreferencesKey("ai_todo_remind_at")
        private val KEY_CATEGORY_OVERRIDES = stringPreferencesKey("category_overrides")
        private val KEY_PURE_MODE = booleanPreferencesKey("pure_mode")
        private val KEY_TODO_ENABLED = booleanPreferencesKey("todo_enabled")
        private val KEY_CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
        private val KEY_DIARY_ENABLED = booleanPreferencesKey("diary_enabled")
        private val KEY_DIARY_AUTO_REPLY = booleanPreferencesKey("diary_auto_reply")
        private val KEY_MODEL_VISION = booleanPreferencesKey("model_vision")

        private val json = Json { ignoreUnknownKeys = true }

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }

    data class AiConfig(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val personalityId: String = "gentle",
        val customPrompt: String = ""
    )

    data class NotifyConfig(
        val dailySummaryEnabled: Boolean = true,
        val dailySummaryHour: Int = 20,
        val dailySummaryMinute: Int = 0,
        val notificationEnabled: Boolean = true
    )

    /** 屏幕时间提醒配置 */
    data class ScreenTimeConfig(
        val enabled: Boolean = true,
        val thresholdMinutes: Int = 30,
        val lastWarnPkg: String = "",
        val lastWarnAt: Long = 0L
    )

    /** 功能模块开关：关闭后底部导航与首页不再显示对应入口/卡片 */
    data class ModuleConfig(
        val todoEnabled: Boolean = true,
        val calendarEnabled: Boolean = true,
        val diaryEnabled: Boolean = true
    )

    // ---------- AI 配置 ----------
    val aiConfig: Flow<AiConfig> = context.dataStore.data.map { p ->
        AiConfig(
            baseUrl = p[KEY_API_BASE_URL] ?: DEFAULT_BASE_URL,
            apiKey = p[KEY_API_KEY] ?: "",
            model = p[KEY_MODEL] ?: DEFAULT_MODEL,
            personalityId = p[KEY_PERSONALITY_ID] ?: "gentle",
            customPrompt = p[KEY_CUSTOM_PROMPT] ?: ""
        )
    }

    suspend fun saveAiConfig(
        baseUrl: String = DEFAULT_BASE_URL,
        apiKey: String = "",
        model: String = DEFAULT_MODEL,
        personalityId: String = "gentle",
        customPrompt: String = ""
    ) {
        context.dataStore.edit { p ->
            p[KEY_API_BASE_URL] = baseUrl
            p[KEY_API_KEY] = apiKey
            p[KEY_MODEL] = model
            p[KEY_PERSONALITY_ID] = personalityId
            p[KEY_CUSTOM_PROMPT] = customPrompt
        }
    }

    // ---------- 通知配置 ----------
    val notifyConfig: Flow<NotifyConfig> = context.dataStore.data.map { p ->
        NotifyConfig(
            dailySummaryEnabled = p[KEY_DAILY_SUMMARY_ENABLED] ?: true,
            dailySummaryHour = p[KEY_DAILY_SUMMARY_HOUR] ?: 20,
            dailySummaryMinute = p[KEY_DAILY_SUMMARY_MINUTE] ?: 0,
            notificationEnabled = p[KEY_NOTIFICATION_ENABLED] ?: true
        )
    }

    suspend fun saveNotifyConfig(
        dailySummaryEnabled: Boolean,
        dailySummaryHour: Int,
        dailySummaryMinute: Int,
        notificationEnabled: Boolean
    ) {
        context.dataStore.edit { p ->
            p[KEY_DAILY_SUMMARY_ENABLED] = dailySummaryEnabled
            p[KEY_DAILY_SUMMARY_HOUR] = dailySummaryHour
            p[KEY_DAILY_SUMMARY_MINUTE] = dailySummaryMinute
            p[KEY_NOTIFICATION_ENABLED] = notificationEnabled
        }
    }

    // ---------- 屏幕时间 ----------
    val screenTimeConfig: Flow<ScreenTimeConfig> = context.dataStore.data.map { p ->
        ScreenTimeConfig(
            enabled = p[KEY_SCREEN_TIME_ENABLED] ?: true,
            thresholdMinutes = p[KEY_SCREEN_TIME_THRESHOLD] ?: 30,
            lastWarnPkg = p[KEY_SCREEN_TIME_WARN_PKG] ?: "",
            lastWarnAt = p[KEY_SCREEN_TIME_WARN_AT] ?: 0L
        )
    }

    suspend fun saveScreenTimeConfig(enabled: Boolean, thresholdMinutes: Int) {
        context.dataStore.edit { p ->
            p[KEY_SCREEN_TIME_ENABLED] = enabled
            p[KEY_SCREEN_TIME_THRESHOLD] = thresholdMinutes
        }
    }

    /** 记录最近一次娱乐超时提醒（用于去重，避免轰炸） */
    suspend fun saveScreenTimeWarn(pkg: String, at: Long) {
        context.dataStore.edit { p ->
            p[KEY_SCREEN_TIME_WARN_PKG] = pkg
            p[KEY_SCREEN_TIME_WARN_AT] = at
        }
    }

    // ---------- Magic AI 待办提醒 ----------
    /** 最近一次 AI 待办提醒时间（用于至少间隔 1 小时） */
    val lastAiTodoRemindAt: Flow<Long> = context.dataStore.data.map { p ->
        p[KEY_AI_TODO_REMIND_AT] ?: 0L
    }

    suspend fun saveAiTodoRemindAt(at: Long) {
        context.dataStore.edit { p -> p[KEY_AI_TODO_REMIND_AT] = at }
    }

    // ---------- 纯净模式 ----------
    /** 纯净模式：开启后关闭后台保活、后台计时与所有提醒，应用完全静默 */
    val pureMode: Flow<Boolean> = context.dataStore.data.map { p ->
        p[KEY_PURE_MODE] ?: false
    }

    suspend fun savePureMode(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_PURE_MODE] = enabled }
    }

    // ---------- 功能模块开关 ----------
    val moduleConfig: Flow<ModuleConfig> = context.dataStore.data.map { p ->
        ModuleConfig(
            todoEnabled = p[KEY_TODO_ENABLED] ?: true,
            calendarEnabled = p[KEY_CALENDAR_ENABLED] ?: true,
            diaryEnabled = p[KEY_DIARY_ENABLED] ?: true
        )
    }

    suspend fun saveModuleConfig(
        todoEnabled: Boolean,
        calendarEnabled: Boolean,
        diaryEnabled: Boolean
    ) {
        context.dataStore.edit { p ->
            p[KEY_TODO_ENABLED] = todoEnabled
            p[KEY_CALENDAR_ENABLED] = calendarEnabled
            p[KEY_DIARY_ENABLED] = diaryEnabled
        }
    }

    // ---------- 日记自动回复 ----------
    val diaryAutoReply: Flow<Boolean> = context.dataStore.data.map { p ->
        p[KEY_DIARY_AUTO_REPLY] ?: false
    }

    suspend fun saveDiaryAutoReply(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_DIARY_AUTO_REPLY] = enabled }
    }

    // ---------- 模型图片识别（vision） ----------
    /** 模型是否支持图片识别；开启后日记自动回复会把日记图片一并注入 AI */
    val modelVision: Flow<Boolean> = context.dataStore.data.map { p ->
        p[KEY_MODEL_VISION] ?: false
    }

    suspend fun saveModelVision(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_MODEL_VISION] = enabled }
    }

    // ---------- 应用自定义分类 ----------
    /** 用户自定义应用分类（包名 -> 类别ID），JSON 序列化存储 */
    val categoryOverrides: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        val raw = p[KEY_CATEGORY_OVERRIDES] ?: return@map emptyMap()
        try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun saveCategoryOverride(pkg: String, category: String) {
        val current = categoryOverrides.first()
        val next = current.toMutableMap().apply { this[pkg] = category }
        context.dataStore.edit { p -> p[KEY_CATEGORY_OVERRIDES] = json.encodeToString(next) }
    }

    suspend fun removeCategoryOverride(pkg: String) {
        val current = categoryOverrides.first()
        val next = current.toMutableMap().apply { remove(pkg) }
        context.dataStore.edit { p -> p[KEY_CATEGORY_OVERRIDES] = json.encodeToString(next) }
    }

    // ---------- 杂项 ----------
    val welcomeShown: Flow<Boolean> = context.dataStore.data.map { p ->
        p[KEY_WELCOME_SHOWN] ?: false
    }

    suspend fun markWelcomeShown() {
        context.dataStore.edit { p -> p[KEY_WELCOME_SHOWN] = true }
    }

    suspend fun setLastSync(time: Long) {
        context.dataStore.edit { p -> p[KEY_LAST_SYNC] = time }
    }

    val lastSync: Flow<Long> = context.dataStore.data.map { p -> p[KEY_LAST_SYNC] ?: 0L }
}
package com.magicnote.mgxd.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 提醒广播接收器
 * - ACTION_TODO_REMIND：单个待办到期提醒
 * - ACTION_DAILY_SUMMARY：每日进度汇总 + 催促（并重排下一天）
 */
class ReminderReceiver : BroadcastReceiver() {

    // 懒加载单例 scope：避免每次广播都新建 CoroutineScope/线程（广播可能高频触发）
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        scope.launch {
            try {
                // 总超时兜底：防止极端情况下（usage 查询慢 + AI 超时叠加）广播 ANR
                withTimeoutOrNull(BROADCAST_TIMEOUT_MS) {
                    // 纯净模式：所有后台任务关闭，残留闹钟直接忽略
                    val app = context.applicationContext as MGApp
                    if (app.container.repository.pureMode.first()) return@withTimeoutOrNull
                    when (intent.action) {
                        ReminderScheduler.ACTION_TODO_REMIND -> {
                            val todoId = intent.getLongExtra(ReminderScheduler.EXTRA_TODO_ID, -1L)
                            if (todoId > 0) handleTodoRemind(context, todoId)
                        }
                        ReminderScheduler.ACTION_DAILY_SUMMARY -> {
                            handleDailySummary(context)
                        }
                        com.magicnote.mgxd.screentime.ScreenTimeMonitor.ACTION_SCREEN_TIME_CHECK -> {
                            com.magicnote.mgxd.screentime.ScreenTimeMonitor.performCheck(context, app.container.repository, scope)
                        }
                        ReminderScheduler.ACTION_DAILY_SCREEN_REPORT -> {
                            handleDailyScreenReport(context)
                        }
                        ReminderScheduler.ACTION_WEEKLY_SCREEN_REPORT -> {
                            handleWeeklyScreenReport(context)
                        }
                        ReminderScheduler.ACTION_DAILY_CLEANUP -> {
                            handleDailyCleanup(context)
                        }
                        ReminderScheduler.ACTION_HABIT_REMIND -> {
                            handleHabitRemind(context, intent)
                        }
                        ReminderScheduler.ACTION_EVENT_REMIND -> {
                            handleEventRemind(context, intent)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        /** 后台广播总时限（系统约 60 秒，留足余量 50 秒） */
        private const val BROADCAST_TIMEOUT_MS = 50_000L
    }

    private suspend fun handleTodoRemind(context: Context, todoId: Long) {
        val app = context.applicationContext as MGApp
        val todo = app.container.repository.getTodo(todoId) ?: return
        if (todo.completed) return // 已完成就不再提醒
        NotificationHelper.showTodoReminder(context, todo)
    }

    private suspend fun handleDailySummary(context: Context) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository

        val config = repo.notifyConfig.collectAsValue()
        if (!config.notificationEnabled || !config.dailySummaryEnabled) return

        // 汇总前清理：删除「已过期且已完成」的待办（已完成的过期任务没有留存价值，避免越积越多）
        runCatching { repo.deleteCompletedExpiredTodosBefore(System.currentTimeMillis()) }

        // 统计今日完成与未完成（只统计今日待办，长期待办不参与日进度）
        val todos = repo.observeTodayTodos().collectAsValue()
        val todayStart = todayStartMillis()
        val todayActive = todos.filter { it.dueTime?.let { d -> d in todayStart..System.currentTimeMillis() } != false }
        // 已完成的数量：用今日待办统计今日完成的（长期待办不计入日进度）
        val allTodos = repo.observeTodayTodos().collectAsValue()
        val todayCompleted = allTodos.count { it.completed && it.createdAt >= todayStart }

        val activeCount = todayActive.size
        val overdueTitles = todayActive
            .filter { it.dueTime?.let { d -> d < System.currentTimeMillis() } == true }
            .map { it.title }

        val aiConfig = repo.aiConfig.collectAsValue()
        val personality = Personality.fromId(aiConfig.personalityId)

        // 功能 C：优先用 Magic AI 生成真·个性化简报；任何异常/超时/未配置 Key 都降级为模板
        val text = if (aiConfig.apiKey.isNotBlank()) {
            try {
                val dayEnd = todayStart + 24 * 60 * 60 * 1000
                val todayEvents = repo.observeAllEvents().collectAsValue()
                    .filter { it.startTime in todayStart until dayEnd }
                val diaries = repo.observeDiaries().collectAsValue()
                val prompt = AiPrompter.buildDailySummaryPrompt(
                    personality = personality,
                    completed = todayCompleted,
                    active = activeCount,
                    overdueTitles = overdueTitles,
                    todos = allTodos,
                    events = todayEvents,
                    diaries = diaries
                )
                withTimeoutOrNull(20_000) {
                    AiClient().chat(
                        baseUrl = aiConfig.baseUrl,
                        apiKey = aiConfig.apiKey,
                        model = aiConfig.model,
                        messages = listOf(AiClient.ChatMessage("user", prompt))
                    )
                } ?: AiPrompter.buildDailySummary(personality, todayCompleted, activeCount, overdueTitles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiPrompter.buildDailySummary(personality, todayCompleted, activeCount, overdueTitles)
            }
        } else {
            AiPrompter.buildDailySummary(personality, todayCompleted, activeCount, overdueTitles)
        }
        NotificationHelper.showDailySummary(context, text)

        // 重排下一天的汇总
        ReminderScheduler.scheduleDailySummary(context, config.dailySummaryHour, config.dailySummaryMinute)
    }

    /** 每日 22:00 屏幕时间日报告（无论成败都重排下一天，保证闹钟不断） */
    private suspend fun handleDailyScreenReport(context: Context) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository
        try {
            val config = repo.screenTimeConfig.collectAsValue()
            if (!config.enabled) return
            if (!com.magicnote.mgxd.screentime.ScreenTimeManager.hasUsageAccess(context)) return

            val overrides = repo.categoryOverrides.collectAsValue()
            val text = com.magicnote.mgxd.screentime.ScreenReportGenerator.buildDailyReport(context, repo, overrides)
            NotificationHelper.showScreenReport(context, "📊 今日屏幕报告", text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 单次生成失败不中断，降级为模板
            NotificationHelper.showScreenReport(
                context, "📊 今日屏幕报告",
                "今天的屏幕使用数据暂时无法生成，明天见 👋"
            )
        } finally {
            ReminderScheduler.scheduleDailyScreenReport(context)
        }
    }

    /** 每周日 08:00 屏幕时间周报告（生成后重排下一周） */
    private suspend fun handleWeeklyScreenReport(context: Context) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository
        try {
            val config = repo.screenTimeConfig.collectAsValue()
            if (!config.enabled) return
            if (!com.magicnote.mgxd.screentime.ScreenTimeManager.hasUsageAccess(context)) return

            val overrides = repo.categoryOverrides.collectAsValue()
            val text = com.magicnote.mgxd.screentime.ScreenReportGenerator.buildWeeklyReport(context, repo, overrides)
            NotificationHelper.showScreenReport(context, "📈 本周屏幕报告", text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NotificationHelper.showScreenReport(
                context, "📈 本周屏幕报告",
                "本周的屏幕使用数据暂时无法生成，下周见 👋"
            )
        } finally {
            ReminderScheduler.scheduleWeeklyScreenReport(context)
        }
    }

    /** 每日 0 点待办清理：删除昨天及以前已完成今日待办（未完成的保留并红字标注，由 UI 展示） */
    private suspend fun handleDailyCleanup(context: Context) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        repo.deleteCompletedTodosBefore(cal.timeInMillis)
        ReminderScheduler.scheduleDailyCleanup(context)
    }

    /** 每日打卡提醒：到点通知 + 重排明天 */
    private suspend fun handleHabitRemind(context: Context, intent: Intent) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository
        val habitId = intent.getLongExtra(ReminderScheduler.EXTRA_HABIT_ID, -1L)
        if (habitId <= 0) return
        val habit = repo.getHabit(habitId) ?: return
        if (habit.remindHour < 0) return
        NotificationHelper.showHabitReminder(context, habit)
        // 重排下一天的提醒（一次性闹钟模式）
        ReminderScheduler.scheduleHabitReminder(context, habit)
    }

    /** 日程提前提醒：到点通知（一次性，无需重排） */
    private suspend fun handleEventRemind(context: Context, intent: Intent) {
        val app = context.applicationContext as MGApp
        val repo = app.container.repository
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
        if (eventId <= 0) return
        val event = repo.getEvent(eventId) ?: return
        NotificationHelper.showEventReminder(context, event)
    }

    private fun todayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

/** 简单扩展：一次性收集 Flow 的首个值（receiver 场景用） */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsValue(): T = first()
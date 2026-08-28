package com.magicnote.mgxd.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.magicnote.mgxd.data.db.CalendarEventEntity
import com.magicnote.mgxd.data.db.HabitEntity
import com.magicnote.mgxd.data.db.TodoEntity
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * 提醒调度器：基于 AlarmManager
 * - 待办到期/设定时间提醒（一次性）
 * - 每日进度汇总（每天，触发后自动重排下一天）
 * - 日程提前提醒（一次性，如提前 10 分钟）
 */
object ReminderScheduler {

    const val ACTION_TODO_REMIND = "com.magicnote.mgxd.action.TODO_REMIND"
    const val ACTION_DAILY_SUMMARY = "com.magicnote.mgxd.action.DAILY_SUMMARY"
    const val ACTION_DAILY_SCREEN_REPORT = "com.magicnote.mgxd.action.DAILY_SCREEN_REPORT"
    const val ACTION_WEEKLY_SCREEN_REPORT = "com.magicnote.mgxd.action.WEEKLY_SCREEN_REPORT"
    const val ACTION_DAILY_CLEANUP = "com.magicnote.mgxd.action.DAILY_CLEANUP"
    const val ACTION_HABIT_REMIND = "com.magicnote.mgxd.action.HABIT_REMIND"
    const val ACTION_EVENT_REMIND = "com.magicnote.mgxd.action.EVENT_REMIND"
    const val EXTRA_TODO_ID = "extra_todo_id"
    const val EXTRA_HABIT_ID = "extra_habit_id"
    const val EXTRA_EVENT_ID = "extra_event_id"

    /** 日报告触发时间 */
    private const val DAILY_REPORT_HOUR = 22
    private const val DAILY_REPORT_MINUTE = 0
    /** 周报告触发时间（每周日 08:00） */
    private const val WEEKLY_REPORT_HOUR = 8
    private const val WEEKLY_REPORT_MINUTE = 0

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(context).canScheduleExactAlarms()
    }

    /** 调度单个待办的提醒 */
    fun scheduleTodoReminder(context: Context, todo: TodoEntity) {
        val remindAt = todo.remindAt ?: todo.dueTime ?: return
        if (remindAt <= System.currentTimeMillis()) return
        if (!todo.completed) {
            scheduleExact(context, remindAt, todoPendingIntent(context, todo.id))
        }
    }

    /** 取消待办提醒 */
    fun cancelTodoReminder(context: Context, todoId: Long) {
        alarmManager(context).cancel(todoPendingIntent(context, todoId))
    }

    /** 取消每日进度汇总 */
    fun cancelDailySummary(context: Context) {
        alarmManager(context).cancel(summaryPendingIntent(context))
    }

    /** 调度每日进度汇总（每天 hour:minute 触发） */
    fun scheduleDailySummary(context: Context, hour: Int, minute: Int) {
        val am = alarmManager(context)
        val pi = summaryPendingIntent(context)
        am.cancel(pi)
        scheduleExact(context, nextTriggerMillis(hour, minute), pi)
    }

    /** 统一调度：有精确闹钟权限用 setExactAndAllowWhileIdle，否则用 setAlarmClock 精确触发 */
    private fun scheduleExact(context: Context, triggerAt: Long, pi: PendingIntent) {
        val am = alarmManager(context)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // 无精确闹钟权限时：setWindow 在 Doze/国产 ROM 省电策略下会被严重延迟甚至不触发（每日汇总失效元凶）。
            // setAlarmClock 无需任何权限、精确触发、Doze 也唤醒（仅状态栏显示一个小闹钟图标，提示用户有定时任务）。
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
        }
    }

    /** 计算下一次触发时间（如果今天已过，排到明天） */
    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** 重新调度所有提醒：启动/开机/升级后调用（一次性读取配置，纯净模式下全部取消） */
    suspend fun rescheduleAll(context: Context, repo: com.magicnote.mgxd.data.repo.AppRepository) {
        // 每日清理兜底：删除昨天及以前创建且已完成的今日待办（0 点闹钟为主，这里保证启动/开机/设置恢复时也清理）
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        runCatching { repo.deleteCompletedTodosBefore(cal.timeInMillis) }
        // 纯净模式：不恢复任何后台任务，全部取消
        if (repo.pureMode.first()) {
            cancelAll(context, repo)
            return
        }
        val todos = repo.getScheduledReminders()
        todos.forEach { scheduleTodoReminder(context, it) }
        val notifyCfg = repo.notifyConfig.first()
        if (notifyCfg.notificationEnabled && notifyCfg.dailySummaryEnabled) {
            scheduleDailySummary(context, notifyCfg.dailySummaryHour, notifyCfg.dailySummaryMinute)
        } else {
            alarmManager(context).cancel(summaryPendingIntent(context))
        }
        // 屏幕时间监控：开启则调度周期检查与日/周报告，关闭则取消
        val stCfg = repo.screenTimeConfig.first()
        if (stCfg.enabled) {
            com.magicnote.mgxd.screentime.ScreenTimeMonitor.scheduleCheck(context)
            scheduleDailyScreenReport(context)
            scheduleWeeklyScreenReport(context)
        } else {
            com.magicnote.mgxd.screentime.ScreenTimeMonitor.cancelCheck(context)
            cancelScreenReports(context)
        }
        // 每日 0 点待办清理
        scheduleDailyCleanup(context)
        // 每日打卡提醒（每个习惯一个每日闹钟）
        repo.observeHabits().first().forEach { scheduleHabitReminder(context, it) }
        // 日程提前提醒（未来未开始的日程）
        repo.observeAllEvents().first().forEach { scheduleEventReminder(context, it) }
    }

    /** 纯净模式/关闭后台时：取消所有闹钟与监控（待办提醒 + 汇总 + 清理 + 日/周报告 + 屏幕监控） */
    suspend fun cancelAll(context: Context, repo: com.magicnote.mgxd.data.repo.AppRepository) {
        val todos = repo.getScheduledReminders()
        todos.forEach { cancelTodoReminder(context, it.id) }
        alarmManager(context).cancel(summaryPendingIntent(context))
        cancelDailyCleanup(context)
        cancelScreenReports(context)
        com.magicnote.mgxd.screentime.ScreenTimeMonitor.cancelCheck(context)
        // 取消所有打卡提醒
        repo.observeHabits().first().forEach { cancelHabitReminder(context, it.id) }
        // 取消所有日程提醒
        repo.observeAllEvents().first().forEach { cancelEventReminder(context, it.id) }
    }

    // ================= 每日打卡提醒 =================

    /** 调度单个打卡习惯的每日提醒（一次性闹钟，触发后由 Receiver 重排下一天） */
    fun scheduleHabitReminder(context: Context, habit: HabitEntity) {
        if (habit.remindHour < 0) return
        val am = alarmManager(context)
        val pi = habitPendingIntent(context, habit.id)
        am.cancel(pi)
        scheduleExact(context, nextTriggerMillis(habit.remindHour, habit.remindMinute), pi)
    }

    /** 取消单个打卡习惯的提醒 */
    fun cancelHabitReminder(context: Context, habitId: Long) {
        alarmManager(context).cancel(habitPendingIntent(context, habitId))
    }

    private fun habitPendingIntent(context: Context, habitId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_HABIT_REMIND
            putExtra(EXTRA_HABIT_ID, habitId)
        }
        return PendingIntent.getBroadcast(
            context,
            habitId.toInt() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ================= 日程提前提醒 =================

    /** 调度单个日程的提前提醒（一次性；remindMinutes<=0 或已过触发时间则忽略） */
    fun scheduleEventReminder(context: Context, event: CalendarEventEntity) {
        if (event.remindMinutes <= 0) return
        val triggerAt = event.startTime - event.remindMinutes * 60_000L
        if (triggerAt <= System.currentTimeMillis()) return
        val am = alarmManager(context)
        val pi = eventPendingIntent(context, event.id)
        am.cancel(pi)
        scheduleExact(context, triggerAt, pi)
    }

    /** 取消单个日程的提前提醒 */
    fun cancelEventReminder(context: Context, eventId: Long) {
        alarmManager(context).cancel(eventPendingIntent(context, eventId))
    }

    private fun eventPendingIntent(context: Context, eventId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_EVENT_REMIND
            putExtra(EXTRA_EVENT_ID, eventId)
        }
        return PendingIntent.getBroadcast(
            context,
            eventId.toInt() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 调度每日 0 点待办清理（已完成的昨日今日待办删除） */
    fun scheduleDailyCleanup(context: Context) {
        val am = alarmManager(context)
        val pi = cleanupPendingIntent(context)
        am.cancel(pi)
        scheduleExact(context, nextTriggerMillis(0, 0), pi)
    }

    /** 取消每日 0 点待办清理 */
    fun cancelDailyCleanup(context: Context) {
        alarmManager(context).cancel(cleanupPendingIntent(context))
    }

    /** 调度每日 22:00 日报告（重复闹钟） */
    fun scheduleDailyScreenReport(context: Context) {
        val am = alarmManager(context)
        val pi = screenReportPendingIntent(context, ACTION_DAILY_SCREEN_REPORT, 7777)
        am.cancel(pi)
        scheduleExact(context, nextTriggerMillis(DAILY_REPORT_HOUR, DAILY_REPORT_MINUTE), pi)
    }

    /** 调度每周日 08:00 周报告 */
    fun scheduleWeeklyScreenReport(context: Context) {
        val am = alarmManager(context)
        val pi = screenReportPendingIntent(context, ACTION_WEEKLY_SCREEN_REPORT, 7778)
        am.cancel(pi)
        scheduleExact(context, nextWeeklyTriggerMillis(), pi)
    }

    /** 取消日/周报告 */
    fun cancelScreenReports(context: Context) {
        val am = alarmManager(context)
        am.cancel(screenReportPendingIntent(context, ACTION_DAILY_SCREEN_REPORT, 7777))
        am.cancel(screenReportPendingIntent(context, ACTION_WEEKLY_SCREEN_REPORT, 7778))
    }

    /** 下一个周日 08:00 的触发时间 */
    private fun nextWeeklyTriggerMillis(): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, WEEKLY_REPORT_HOUR)
            set(Calendar.MINUTE, WEEKLY_REPORT_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Calendar.SUNDAY == 1
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 7)
        }
        return cal.timeInMillis
    }

    private fun screenReportPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun todoPendingIntent(context: Context, todoId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TODO_REMIND
            putExtra(EXTRA_TODO_ID, todoId)
        }
        return PendingIntent.getBroadcast(
            context,
            todoId.toInt() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun summaryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DAILY_SUMMARY
        }
        return PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cleanupPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_DAILY_CLEANUP
        }
        return PendingIntent.getBroadcast(
            context,
            7779,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
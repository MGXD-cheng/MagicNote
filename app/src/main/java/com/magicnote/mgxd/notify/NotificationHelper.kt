package com.magicnote.mgxd.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.magicnote.mgxd.MainActivity
import com.magicnote.mgxd.R
import com.magicnote.mgxd.data.db.TodoEntity

/**
 * 通知工具：负责创建渠道与发送各类通知
 */
object NotificationHelper {

    const val CHANNEL_TODO = "channel_todo_reminder"
    const val CHANNEL_SUMMARY = "channel_daily_summary"
    const val CHANNEL_SCREEN_TIME = "channel_screen_time"
    const val CHANNEL_SCREEN_REPORT = "channel_screen_report"
    const val CHANNEL_AI_TODO = "channel_ai_todo_reminder"
    const val CHANNEL_KEEP_ALIVE = "channel_keep_alive"
    const val CHANNEL_DIARY_REPLY = "channel_diary_reply"
    const val CHANNEL_HABIT = "channel_habit"
    const val CHANNEL_EVENT = "channel_event_reminder"

    private const val NOTIFY_ID_TODO_BASE = 1000
    private const val NOTIFY_ID_SUMMARY = 2000
    private const val NOTIFY_ID_SCREEN_TIME = 3000
    private const val NOTIFY_ID_SCREEN_REPORT = 4000
    private const val NOTIFY_ID_AI_TODO = 5000
    const val NOTIFY_ID_KEEP_ALIVE = 6000
    private const val NOTIFY_ID_DIARY_REPLY = 7000
    private const val NOTIFY_ID_HABIT_BASE = 8000
    private const val NOTIFY_ID_EVENT_BASE = 9000

    /** 渠道是否已初始化（避免每次发通知都重复 IPC 创建渠道） */
    private val channelsReady = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensureChannels(context: Context) {
        // 幂等：系统层面重复创建同名渠道是安全的，但会触发 IPC，这里只初始化一次
        if (!channelsReady.compareAndSet(false, true)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TODO,
                "待办提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "待办事项到期与提醒"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY,
                "每日进度汇总",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每天定时汇总任务进度并催促完成"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREEN_TIME,
                "屏幕时间提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "长时间娱乐时提醒休息（可配合 Magic AI 生成文案）"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREEN_REPORT,
                "屏幕时间报告",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "每天 22:00 日报告、每周日 08:00 周报告"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AI_TODO,
                "Magic AI 提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "待办未完成时，Magic AI 定时生成催促提醒"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_KEEP_ALIVE,
                "后台守护",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "常驻通知，保障提醒与屏幕监控在后台稳定运行"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIARY_REPLY,
                "日记自动回复",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "写完日记后 Magic AI 自动生成回复"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HABIT,
                "每日打卡提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "每天固定时间提醒打卡习惯"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENT,
                "日程提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "日程开始前提前提醒"
                enableVibration(true)
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 待办到期/提醒通知 */
    fun showTodoReminder(context: Context, todo: TodoEntity) {
        val priority = when (todo.priority) { 2 -> "🔴 高优先级"; 1 -> "🟡 中优先级"; else -> "🟢 低优先级" }
        val content = if (todo.dueTime != null && todo.dueTime <= System.currentTimeMillis()) {
            "已到截止时间！$priority"
        } else {
            "该行动啦！$priority"
        }
        notify(
            context, CHANNEL_TODO, NOTIFY_ID_TODO_BASE + todo.id.toInt(),
            "⏰ ${todo.title}", content,
            NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 每日进度汇总 + 催促 */
    fun showDailySummary(context: Context, text: String) {
        notify(
            context, CHANNEL_SUMMARY, NOTIFY_ID_SUMMARY,
            "📋 今日进度汇报", text,
            NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 屏幕时间：娱乐超时提醒 */
    fun showEntertainmentWarning(context: Context, appLabel: String, minutes: Int, text: String) {
        notify(
            context, CHANNEL_SCREEN_TIME, NOTIFY_ID_SCREEN_TIME,
            "🎮 「$appLabel」玩了 $minutes 分钟啦", text,
            NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 屏幕时间：日/周报告通知 */
    fun showScreenReport(context: Context, title: String, text: String) {
        notify(
            context, CHANNEL_SCREEN_REPORT, NOTIFY_ID_SCREEN_REPORT,
            title, text,
            NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** Magic AI：待办未完成催促提醒（AI 生成文案） */
    fun showAiTodoReminder(context: Context, text: String) {
        notify(
            context, CHANNEL_AI_TODO, NOTIFY_ID_AI_TODO,
            "🧠 Magic AI 提醒", text,
            NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 日记自动回复：AI 读完日记后生成的回复 */
    fun showDiaryAutoReply(context: Context, text: String) {
        notify(
            context, CHANNEL_DIARY_REPLY, NOTIFY_ID_DIARY_REPLY,
            "📖 Magic AI 回复了你的日记", text,
            NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.CATEGORY_SOCIAL
        )
    }

    /** 每日打卡提醒：到点催促打卡（附鼓励语） */
    fun showHabitReminder(context: Context, habit: com.magicnote.mgxd.data.db.HabitEntity) {
        val today = java.time.LocalDate.now().toString()
        val done = habit.checkInDates.contains(today)
        val text = if (done) {
            "今天已完成打卡，继续保持！${com.magicnote.mgxd.util.HabitEncouragement.random()}"
        } else {
            "该打卡「${habit.title}」啦！${com.magicnote.mgxd.util.HabitEncouragement.forRemind()}"
        }
        notify(
            context, CHANNEL_HABIT, NOTIFY_ID_HABIT_BASE + (habit.id.toInt() and 0x7FFFFFFF),
            "🔥 每日打卡：${habit.title}", text,
            NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 日程提前提醒：开始前 remindMinutes 分钟通知 */
    fun showEventReminder(context: Context, event: com.magicnote.mgxd.data.db.CalendarEventEntity) {
        val timeStr = com.magicnote.mgxd.util.TimeUtils.formatMillis(event.startTime, "HH:mm")
        val text = "「${event.title}」将于 $timeStr 开始" +
            if (event.remindMinutes > 0) "（提前 ${event.remindMinutes} 分钟提醒）" else ""
        notify(
            context, CHANNEL_EVENT, NOTIFY_ID_EVENT_BASE + (event.id.toInt() and 0x7FFFFFFF),
            "🔔 ${event.title}", text,
            NotificationCompat.PRIORITY_HIGH, NotificationCompat.CATEGORY_REMINDER
        )
    }

    /** 统一通知构建与发送：所有提醒共用一套模板 */
    private fun notify(
        context: Context,
        channelId: String,
        notifyId: Int,
        title: String,
        text: String,
        priority: Int,
        category: String
    ) {
        if (!hasPermission(context)) return
        ensureChannels(context)
        val notification: Notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifyId, notification)
    }

    /** 后台守护常驻通知（前台服务用，低打扰、不可滑动清除） */
    fun buildKeepAliveNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Magic note 守护中")
            .setContentText("屏幕监控与 AI 提醒正在后台运行")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    private fun hasPermission(context: Context): Boolean {
        return android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
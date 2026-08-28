package com.magicnote.mgxd.screentime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.NotificationHelper
import com.magicnote.mgxd.notify.ReminderReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕时间监控：基于 AlarmManager 周期检查
 * - 每 15 分钟检查一次当前前台应用
 * - 若为娱乐应用且连续使用超过阈值 → 发送 AI 提醒通知
 * - 同应用 30 分钟内去重，避免轰炸
 */
object ScreenTimeMonitor {

    const val ACTION_SCREEN_TIME_CHECK = "com.magicnote.mgxd.action.SCREEN_TIME_CHECK"

    private const val REQUEST_CODE = 8888

    /** 最小检查间隔：与 KeepAliveService 轮询一致（5 分钟），消除轮询与 Alarm 兜底的双重触发 */
    private const val MIN_CHECK_INTERVAL_MS = 5 * 60 * 1000L

    /** 上次执行检查的时间戳（进程内节流；服务被杀后 Alarm 兜底重新计时） */
    @Volatile
    private var lastCheckAt = 0L

    /** 调度周期检查（重复闹钟，低功耗模式；KeepAliveService 被杀后兜底） */
    fun scheduleCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = checkPendingIntent(context)
        am.cancel(pi)
        val interval = ScreenTimeManager.CHECK_INTERVAL_MS
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + interval,
            interval,
            pi
        )
    }

    fun cancelCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(checkPendingIntent(context))
    }

    private fun checkPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SCREEN_TIME_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 执行一次检查（在 IO 协程中调用） */
    fun performCheck(context: Context, repo: AppRepository, scope: CoroutineScope) {
        // 节流：KeepAliveService 每 5 分钟轮询 + AlarmManager 每 15 分钟兜底，
        // 两者共用此标记，保证实际查询频率 ≤ 每 5 分钟一次（usage 查询较重，避免拖垮系统）
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAt = now

        scope.launch {
            try {
                val config = repo.screenTimeConfig.first()
                if (!config.enabled) return@launch
                if (!ScreenTimeManager.hasUsageAccess(context)) return@launch
                // 节能：息屏时不检查（无人使用，且 session 可能被息屏时长虚增）
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) return@launch

                val pkg = ScreenTimeManager.getForegroundPackage(context) ?: return@launch
                val overrides = repo.categoryOverrides.first()
                if (!ScreenTimeManager.isEntertainmentApp(pkg, overrides)) return@launch

                val session = ScreenTimeManager.getCurrentSessionMillis(context, pkg)
                val thresholdMs = config.thresholdMinutes * 60 * 1000L
                if (session < thresholdMs) return@launch

                // 去重：同一应用 30 分钟内只提醒一次
                if (config.lastWarnPkg == pkg && now - config.lastWarnAt < ScreenTimeManager.WARN_DEDUP_MS) {
                    return@launch
                }

                val label = ScreenTimeManager.getAppLabel(context, pkg)
                val minutes = (session / 60_000L).coerceAtLeast(1L).toInt()
                val text = generateWarnText(context, repo, label, minutes)

                NotificationHelper.showEntertainmentWarning(context, label, minutes, text)
                repo.saveScreenTimeWarn(pkg, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默失败：不打断正常使用
            }
        }
    }

    /** 生成提醒文案：优先 AI 个性化，失败降级模板 */
    private suspend fun generateWarnText(
        context: Context,
        repo: AppRepository,
        label: String,
        minutes: Int
    ): String {
        val template = "你已经在「$label」上连续玩了 $minutes 分钟啦，起来活动一下，别忘了今天的小目标哦 🪄"
        return try {
            val aiConfig = repo.aiConfig.first()
            if (aiConfig.apiKey.isBlank()) return template

            val todos = repo.observeActiveTodos().first()
            val personality = Personality.fromId(aiConfig.personalityId)
            val prompt = AiPrompter.buildEntertainmentWarnPrompt(
                personality = personality,
                appLabel = label,
                minutes = minutes,
                todos = todos
            )
            withTimeoutOrNull(15_000) {
                AiClient().chat(
                    baseUrl = aiConfig.baseUrl,
                    apiKey = aiConfig.apiKey,
                    model = aiConfig.model,
                    messages = listOf(AiClient.ChatMessage("user", prompt))
                )
            } ?: template
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            template
        }
    }
}
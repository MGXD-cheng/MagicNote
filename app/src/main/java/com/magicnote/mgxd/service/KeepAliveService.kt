package com.magicnote.mgxd.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.magicnote.mgxd.MGApp
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.repo.AppRepository
import com.magicnote.mgxd.notify.NotificationHelper
import com.magicnote.mgxd.screentime.ScreenTimeMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/**
 * 后台守护前台服务（保活）
 *
 * 职责：
 * 1. 轮询执行屏幕时间娱乐超时检查 —— 替代不可靠的 setInexactRepeating
 * 2. 待办未完成时，Magic AI 每 ≥1 小时生成一次催促提醒
 * 3. 常驻前台（START_STICKY + 低打扰通知），被杀后系统自动重启，实现后台保活
 *
 * 节能策略（v3.4）：
 * - 亮屏时才检查（息屏无人使用，整轮跳过，夜间几乎零耗电）
 * - 亮屏轮询间隔 5 分钟（阈值 30 分钟，最多延迟 5 分钟，体验无损）
 * - 无待办任务时跳过 AI 提醒检查
 *
 * AI 催促策略（v3.6）：
 * - 夜间 22:00 - 06:00 不催促
 * - 今日待办未完成：每 ≥1 小时催一次
 * - 今日全部完成、只剩长期待办：每晚 20:00 催一次
 *
 * 前台服务类型 specialUse（Android 14+ 专用，无运行时长限制）。
 */
class KeepAliveService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 前台服务必须立刻发布通知（startForegroundService 5 秒内）
        NotificationHelper.ensureChannels(this)
        startForeground(
            NotificationHelper.NOTIFY_ID_KEEP_ALIVE,
            NotificationHelper.buildKeepAliveNotification(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTickLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTickLoop() {
        if (tickJob?.isActive == true) return
        tickJob = serviceScope.launch {
            while (isActive) {
                try {
                    runChecks()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单项失败不中断循环
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private suspend fun runChecks() {
        // 纯净模式：后台功能全部关闭，直接跳过
        val app = applicationContext as MGApp
        val repo = app.container.repository
        if (repo.pureMode.first()) return

        // 节能：息屏时无人使用，整轮跳过检查（夜间几乎零耗电）
        if (!isScreenOn()) return

        // 1) 屏幕时间：娱乐超时提醒（内部有会话时长判定 + 30 分钟去重）
        ScreenTimeMonitor.performCheck(applicationContext, repo, serviceScope)

        // 2) 待办未完成：Magic AI 催促提醒（无待办自动跳过，且至少间隔 1 小时）
        checkAiTodoRemind(repo)
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isInteractive
    }

    /**
     * 待办 AI 提醒（v3.6 智能调度）：
     * - 夜间 22:00 - 06:00 不催促
     * - 今日待办未完成 → 每 ≥1 小时催一次
     * - 今日待办已全部完成、只剩长期待办 → 每晚 20:00 催一次
     * - 全部完成 → 不催
     */
    private suspend fun checkAiTodoRemind(repo: AppRepository) {
        val nowTime = LocalDateTime.now()
        val hour = nowTime.hour
        // 夜间 22:00 - 06:00 静默，不打扰
        if (hour >= NIGHT_SILENT_START_HOUR || hour < NIGHT_SILENT_END_HOUR) return

        val active = repo.observeActiveTodos().first()
        if (active.isEmpty()) return

        val todayPending = active.filter { !it.isLongTerm }
        val longTermPending = active.filter { it.isLongTerm }
        val now = System.currentTimeMillis()
        val last = repo.observeLastAiTodoRemindAt().first()
        val sinceLast = now - last

        val allowed = when {
            // 今日待办未完成：保持 ≥1 小时一次的节奏
            todayPending.isNotEmpty() -> sinceLast >= AI_REMIND_INTERVAL_MS
            // 今日待办已全部完成、只剩长期待办：每晚 20:00（20:00-20:29 窗口）催一次
            longTermPending.isNotEmpty() ->
                hour == DAILY_URGE_HOUR && nowTime.minute < DAILY_URGE_MINUTE_WINDOW &&
                    sinceLast >= DAILY_URGE_INTERVAL_MS
            // 全部完成：不催
            else -> false
        }
        if (!allowed) return

        val text = generateTodoUrge(repo, active)
        NotificationHelper.showAiTodoReminder(this, text)
        repo.saveLastAiTodoRemindAt(now)
    }

    /** 生成催促文案：优先 Magic AI 个性化，未配置 Key / 超时 / 异常降级为模板 */
    private suspend fun generateTodoUrge(repo: AppRepository, active: List<com.magicnote.mgxd.data.db.TodoEntity>): String {
        val sample = active.take(3).joinToString("、") { it.title }
        val more = if (active.size > 3) "…" else ""
        val template = "你还有 ${active.size} 项待办没完成：$sample$more。别让它们陪你过夜，挑一件先开始吧 💪"
        return try {
            val cfg = repo.aiConfig.first()
            if (cfg.apiKey.isBlank()) return template
            val personality = Personality.fromId(cfg.personalityId)
            val prompt = AiPrompter.buildTodoUrgePrompt(personality, active)
            withTimeoutOrNull(15_000) {
                AiClient().chat(
                    baseUrl = cfg.baseUrl,
                    apiKey = cfg.apiKey,
                    model = cfg.model,
                    messages = listOf(AiClient.ChatMessage("user", prompt))
                )
            } ?: template
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            template
        }
    }

    companion object {
        /** 亮屏轮询间隔：5 分钟（阈值 30 分钟，最多延迟 5 分钟；息屏跳过检查） */
        const val TICK_INTERVAL_MS = 5 * 60 * 1000L

        /** AI 待办提醒最小间隔：1 小时（今日待办未完成时） */
        const val AI_REMIND_INTERVAL_MS = 60 * 60 * 1000L

        /** 夜间静默：22 点起不催促 */
        const val NIGHT_SILENT_START_HOUR = 22

        /** 夜间静默：早 6 点恢复 */
        const val NIGHT_SILENT_END_HOUR = 6

        /** 长期待办专属催促：晚上 8 点 */
        const val DAILY_URGE_HOUR = 20

        /** 晚上 8 点的催促窗口（20:00-20:29） */
        const val DAILY_URGE_MINUTE_WINDOW = 30

        /** 长期待办每天最多催一次：间隔 ≥23 小时防重复 */
        const val DAILY_URGE_INTERVAL_MS = 23 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止后台守护（纯净模式） */
        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
package com.magicnote.mgxd.screentime

import android.content.Context
import com.magicnote.mgxd.ai.AiClient
import com.magicnote.mgxd.ai.AiPrompter
import com.magicnote.mgxd.ai.Personality
import com.magicnote.mgxd.data.repo.AppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.magicnote.mgxd.util.TimeUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 屏幕时间报告生成器
 * - 日报告：每天 22:00 生成（今日统计）
 * - 周报告：每周日 08:00 生成（近 7 天统计）
 * - 优先用 Magic AI 生成个性化总结，失败降级为本地模板
 */
object ScreenReportGenerator {

    /** 生成今日日报告文案 */
    suspend fun buildDailyReport(context: Context, repo: AppRepository, overrides: Map<String, String>): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()
        val total = ScreenTimeManager.getTodayUsageMillis(context)
        if (total <= 0L) return "今天还没怎么用手机，继续保持这份克制 🎉"
        val cats = ScreenTimeManager.getCategoryUsage(context, start, end, overrides)
        val template = buildDailyText(total, cats)
        return aiEnhance(context, repo, template, "日报告") ?: template
    }

    /** 生成近 7 天周报告文案 */
    suspend fun buildWeeklyReport(context: Context, repo: AppRepository, overrides: Map<String, String>): String {
        val end = System.currentTimeMillis()
        val start = end - 7 * 24 * 3600 * 1000L
        val total = sumMillis(context, start, end)
        if (total <= 0L) return "本周还没有屏幕使用数据，出去走走吧 🌤️"
        val cats = ScreenTimeManager.getCategoryUsage(context, start, end, overrides)
        val topApp = ScreenTimeManager.getAppUsages(context, start, end, limit = 1).firstOrNull()
        val template = buildWeeklyText(start, end, total, cats, topApp)
        return aiEnhance(context, repo, template, "周报告") ?: template
    }

    /** 指定范围总使用时长 */
    private fun sumMillis(context: Context, start: Long, end: Long): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        return usm.queryUsageStats(
            android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end
        ).sumOf { it.totalTimeInForeground }
    }

    /** 日报告模板文案 */
    private fun buildDailyText(total: Long, cats: List<ScreenTimeManager.CategoryUsage>): String {
        val sb = StringBuilder()
        sb.append("📊 今日屏幕报告\n")
        sb.append("· 使用时长：").append(fmt(total)).append("\n")
        cats.forEach { c ->
            val pct = if (total > 0) (c.millis * 100 / total) else 0
            sb.append("· ").append(c.category.label).append("：")
                .append(fmt(c.millis)).append("（").append(pct).append("%）\n")
        }
        val ent = cats.firstOrNull { it.category == ScreenTimeManager.AppCategory.ENTERTAINMENT }
        if (ent != null && total > 0 && ent.millis * 100 / total >= 50) {
            sb.append("· 建议：娱乐占比过半啦，明天试试番茄工作法？")
        } else {
            sb.append("· 建议：继续保持合理的使用节奏！")
        }
        return sb.toString()
    }

    /** 周报告模板文案 */
    private fun buildWeeklyText(
        start: Long, end: Long, total: Long,
        cats: List<ScreenTimeManager.CategoryUsage>,
        topApp: ScreenTimeManager.AppUsage?
    ): String {
        val fmtDate = SimpleDateFormat("M月d日", Locale.getDefault())
        val days = 7
        val avg = total / days
        val sb = StringBuilder()
        sb.append("📈 本周屏幕报告（").append(fmtDate.format(Date(start)))
            .append(" - ").append(fmtDate.format(Date(end))).append("）\n")
        sb.append("· 总时长：").append(fmt(total)).append("\n")
        sb.append("· 日均：").append(fmt(avg)).append("\n")
        cats.forEach { c ->
            val pct = if (total > 0) (c.millis * 100 / total) else 0
            sb.append("· ").append(c.category.label).append("：")
                .append(fmt(c.millis)).append("（").append(pct).append("%）\n")
        }
        topApp?.let { sb.append("· 使用最多：").append(it.label).append(" ").append(fmt(it.millis)).append("\n") }
        val ent = cats.firstOrNull { it.category == ScreenTimeManager.AppCategory.ENTERTAINMENT }
        if (ent != null && total > 0 && ent.millis * 100 / total >= 50) {
            sb.append("· 建议：娱乐占比偏高，下周给每个应用设个时间上限吧")
        } else {
            sb.append("· 建议：整体节奏不错，继续保持！")
        }
        return sb.toString()
    }

    /** 调用 Magic AI 润色报告（失败返回 null 走模板） */
    private suspend fun aiEnhance(
        context: Context,
        repo: AppRepository,
        template: String,
        reportType: String
    ): String? = try {
        val aiConfig = repo.aiConfig.first()
        if (aiConfig.apiKey.isBlank()) return null
        val personality = Personality.fromId(aiConfig.personalityId)
        val prompt = AiPrompter.buildScreenReportPrompt(personality, reportType, template)
        withTimeoutOrNull(15_000) {
            AiClient().chat(
                baseUrl = aiConfig.baseUrl,
                apiKey = aiConfig.apiKey,
                model = aiConfig.model,
                messages = listOf(AiClient.ChatMessage("user", prompt))
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fmt(millis: Long): String = TimeUtils.formatDuration(millis)
}
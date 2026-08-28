package com.magicnote.mgxd.screentime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * 屏幕时间管理器：基于 UsageStatsManager 统计手机与应用使用时长
 * - 需要用户在系统设置中开启「使用情况访问权限」（PACKAGE_USAGE_STATS）
 * - 娱乐应用判定：内置常见娱乐（短视频/长视频/游戏/社交内容）包名黑名单
 */
object ScreenTimeManager {

    /** 默认娱乐超时提醒阈值（分钟） */
    const val DEFAULT_THRESHOLD_MINUTES = 30

    /** 周期检查间隔：15 分钟 */
    const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    /** 同应用提醒去重窗口：30 分钟内不重复轰炸 */
    const val WARN_DEDUP_MS = 30 * 60 * 1000L

    private val ENTERTAINMENT_PKGS = setOf(
        // 短视频 / 直播
        "com.ss.android.ugc.aweme",        // 抖音
        "com.ss.android.ugc.live",         // 抖音火山版
        "com.smile.gifmaker",              // 快手
        "com.kuaishou.nebula",             // 快手极速版
        "com.zhiliaoapp.musically",        // TikTok
        // 长视频
        "tv.danmaku.bili",                 // 哔哩哔哩
        "com.tencent.qqlive",              // 腾讯视频
        "com.qiyi.video",                  // 爱奇艺
        "com.youku.phone",                 // 优酷
        "com.hunantv.imgo.activity",       // 芒果TV
        "com.netflix.mediaclient",         // Netflix
        "com.google.android.youtube",      // YouTube
        // 游戏
        "com.tencent.tmgp.sgame",          // 王者荣耀
        "com.tencent.tmgp.pubgmhd",        // 和平精英
        "com.miHoYo.Yuanshen",             // 原神
        "com.miHoYo.hkrpg",                // 崩坏：星穹铁道
        "com.mojang.minecraftpe",          // 我的世界
        "com.roblox.client",               // Roblox
        "com.supercell.clashofclans",      // 部落冲突
        "com.supercell.brawlstars",        // 荒野乱斗
        "com.netease.dwrg",                // 第五人格
        "com.netease.onmyoji",             // 阴阳师
        "com.tencent.tmgp.dnf",            // DNF 手游
        "com.tencent.tmgp.cf",             // 穿越火线手游
        // 内容社区（易沉迷）
        "com.xingin.xhs",                  // 小红书
        "com.tencent.weibo",               // 微博
        "com.zhihu.android"                // 知乎
    )

    /** 应用类别（可自定义，用户覆盖优先于内置规则） */
    enum class AppCategory(val label: String) {
        ENTERTAINMENT("娱乐"), SOCIAL("社交"), SHOPPING("购物"),
        TOOLS("工具"), STUDY("学习"), OTHER("其他");

        companion object {
            fun fromId(id: String?): AppCategory? =
                entries.firstOrNull { it.name == id }
        }
    }

    /** 单类别使用时长 */
    data class CategoryUsage(val category: AppCategory, val millis: Long)

    /** 内置默认分类规则（用户覆盖 > 内置规则 > 其他） */
    private val DEFAULT_CATEGORY_RULES: Map<String, AppCategory> = buildMap {
        // 娱乐（复用黑名单）
        ENTERTAINMENT_PKGS.forEach { put(it, AppCategory.ENTERTAINMENT) }
        // 社交
        put("com.tencent.mm", AppCategory.SOCIAL)                    // 微信
        put("com.tencent.mobileqq", AppCategory.SOCIAL)              // QQ
        put("com.dingtalk.android", AppCategory.SOCIAL)              // 钉钉
        put("com.alibaba.android.rimet", AppCategory.SOCIAL)         // 钉钉旧包
        put("com.ss.android.lark", AppCategory.SOCIAL)               // 飞书
        put("org.telegram.messenger", AppCategory.SOCIAL)            // Telegram
        put("com.whatsapp", AppCategory.SOCIAL)                      // WhatsApp
        put("com.instagram.android", AppCategory.SOCIAL)             // Instagram
        put("com.facebook.katana", AppCategory.SOCIAL)               // Facebook
        put("com.twitter.android", AppCategory.SOCIAL)               // X
        put("com.snapchat.android", AppCategory.SOCIAL)              // Snapchat
        // 购物
        put("com.taobao.taobao", AppCategory.SHOPPING)               // 淘宝
        put("com.tmall.wireless", AppCategory.SHOPPING)              // 天猫
        put("com.jingdong.app.mall", AppCategory.SHOPPING)           // 京东
        put("com.xunmeng.pinduoduo", AppCategory.SHOPPING)           // 拼多多
        put("com.taobao.idlefish", AppCategory.SHOPPING)             // 闲鱼
        put("com.sankuai.meituan", AppCategory.SHOPPING)             // 美团
        put("me.ele", AppCategory.SHOPPING)                          // 饿了么
        put("com.amazon.mShop.android.shopping", AppCategory.SHOPPING) // 亚马逊
        // 工具
        put("com.android.chrome", AppCategory.TOOLS)                 // Chrome
        put("com.microsoft.emmx", AppCategory.TOOLS)                 // Edge
        put("com.tencent.mtt", AppCategory.TOOLS)                    // QQ浏览器
        put("com.google.android.gm", AppCategory.TOOLS)              // Gmail
        put("com.microsoft.office.outlook", AppCategory.TOOLS)       // Outlook
        put("com.google.android.apps.maps", AppCategory.TOOLS)       // 地图
        put("com.google.android.apps.photos", AppCategory.TOOLS)     // 相册
        put("com.tencent.wework", AppCategory.TOOLS)                 // 企业微信
        // 学习
        put("com.duolingo", AppCategory.STUDY)                       // 多邻国
        put("com.google.android.apps.classroom", AppCategory.STUDY)  // 谷歌课堂
        put("com.youdao.dict", AppCategory.STUDY)                    // 有道词典
        put("com.baidu.searchbox", AppCategory.TOOLS)                // 百度
    }

    /** 判定应用类别：用户覆盖 > 内置规则 > 其他 */
    fun categorize(pkg: String, overrides: Map<String, String>): AppCategory {
        overrides[pkg]?.let { raw ->
            AppCategory.fromId(raw)?.let { return it }
        }
        return DEFAULT_CATEGORY_RULES[pkg] ?: AppCategory.OTHER
    }

    /** 是否已授予使用情况访问权限 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 跳转到系统「使用情况访问权限」设置页 */
    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 部分机型没有该 ACTION，退回应用详情页
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /** 是否娱乐类应用（尊重用户自定义分类） */
    fun isEntertainmentApp(pkg: String, overrides: Map<String, String> = emptyMap()): Boolean =
        categorize(pkg, overrides) == AppCategory.ENTERTAINMENT

    /** 应用显示名（取不到则回退包名） */
    fun getAppLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** 今日手机总使用时长（毫秒） */
    fun getTodayUsageMillis(context: Context): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = todayStartMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
        return stats.sumOf { it.totalTimeInForeground }
    }

    /** 应用使用时长排行（按时长降序） */
    data class AppUsage(val pkg: String, val label: String, val millis: Long)

    fun getTodayAppUsages(context: Context, limit: Int = 10): List<AppUsage> {
        val start = todayStartMillis()
        return getAppUsages(context, start, System.currentTimeMillis(), limit)
    }

    /** 指定时间范围内的应用使用排行 */
    fun getAppUsages(context: Context, startMillis: Long, endMillis: Long, limit: Int = 10): List<AppUsage> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMillis, endMillis)
        return stats
            .filter { it.packageName != context.packageName && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .map { AppUsage(it.packageName, getAppLabel(context, it.packageName), it.totalTimeInForeground) }
    }

    /** 指定时间范围内的类别使用时长分布 */
    fun getCategoryUsage(
        context: Context,
        startMillis: Long,
        endMillis: Long,
        overrides: Map<String, String>
    ): List<CategoryUsage> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMillis, endMillis)
        val map = mutableMapOf<AppCategory, Long>()
        stats.forEach { st ->
            if (st.packageName == context.packageName) return@forEach
            val cat = categorize(st.packageName, overrides)
            map[cat] = map.getOrDefault(cat, 0L) + st.totalTimeInForeground
        }
        return map.map { CategoryUsage(it.key, it.value) }.sortedByDescending { it.millis }
    }

    /** 当前前台应用包名（最近一次 MOVE_TO_FOREGROUND） */
    fun getForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 10 * 60 * 1000L, end)
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            val e = UsageEvents.Event()
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = e.packageName
            }
        }
        return lastPkg
    }

    /** 指定应用当前连续使用时长（毫秒）：从最近一次进入前台起，未被切后台打断 */
    fun getCurrentSessionMillis(context: Context, pkg: String): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 6 * 3600 * 1000L, end)
        var sessionStart = 0L
        var inForeground = false
        while (events.hasNextEvent()) {
            val e = UsageEvents.Event()
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (!inForeground) {
                        sessionStart = e.timeStamp
                        inForeground = true
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    inForeground = false
                }
            }
        }
        return if (inForeground && sessionStart > 0) end - sessionStart else 0L
    }

    private fun todayStartMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}

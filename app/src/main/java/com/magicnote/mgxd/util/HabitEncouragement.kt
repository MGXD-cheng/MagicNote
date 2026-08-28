package com.magicnote.mgxd.util

import java.time.LocalDate

/**
 * 每日打卡鼓励文案生成器
 * 按连续打卡天数递进，让坚持看得见
 */
object HabitEncouragement {

    /** 计算当前连续打卡天数（今天已打卡从今天起算；今天没打卡但从昨天连续也算） */
    fun streakOf(dates: List<String>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.sortedDescending()
        var cursor = LocalDate.parse(sorted.first())
        // 今天还没打卡不算断签，从昨天开始向前数
        if (cursor != LocalDate.now()) cursor = cursor.minusDays(1)
        var streak = 0
        while (sorted.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 打卡成功时的鼓励（按连续天数递进） */
    fun forCheckIn(streak: Int): String = when {
        streak >= 100 -> "🎉 连续 $streak 天！你就是行走的自律传说！"
        streak >= 30 -> "🌟 连续 $streak 天！习惯已成自然，太强了！"
        streak >= 21 -> "🔥 连续 $streak 天！21 天定律达成，这个习惯属于你了！"
        streak >= 7 -> "💪 连续 $streak 天！坚持一周，进步肉眼可见！"
        streak >= 3 -> "✨ 连续 $streak 天！势头正好，千万别停！"
        else -> random()
    }

    /** 每日随机小鼓励（首日/日常打卡） */
    fun random(): String = listOf(
        "太棒了！今天又进步了一点点 ✨",
        "坚持就是胜利，为你点赞 👍",
        "每一次打卡，都是未来的你在感谢现在的你 🌱",
        "好样的！自律的你闪闪发光 ✨",
        "完成今日打卡，离目标又近了一步 🚀",
        "今天也好好爱自己，从打卡开始 💛"
    ).random()

    /** 提醒通知用：催促打卡 */
    fun forRemind(): String = listOf(
        "今天打卡了吗？一分钟搞定，别让坚持断档 ⏰",
        "习惯的养成靠每一天，现在就来打卡吧 💪",
        "别忘了今天的约定，我在这里等你 🔥",
        "连续打卡的火焰，别让它灭掉呀 ✨"
    ).random()
}
package com.magicnote.mgxd.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间格式化公共工具：消除各页面重复的 formatTime 实现
 */
object TimeUtils {

    /** 毫秒时间戳 → 本地时间字符串（默认 MM-dd HH:mm） */
    fun formatMillis(millis: Long, pattern: String = "MM-dd HH:mm"): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern))

    /** 毫秒时间戳 → 本地时间字符串（自定义 DateTimeFormatter） */
    fun formatMillis(millis: Long, fmt: DateTimeFormatter): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmt)

    /** 时长毫秒 → 「x小时y分钟 / y分钟」 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0分钟"
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
    }
}

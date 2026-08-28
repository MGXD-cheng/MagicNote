package com.magicnote.mgxd.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 倒数日实体（如距离高考/生日/纪念日还有多少天）
 * @param targetDate 目标日当天 0 点毫秒时间戳
 */
@Entity(tableName = "countdowns")
data class CountdownEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetDate: Long,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 距离目标日的天数差：>0 还有 N 天；==0 就是今天；<0 已过 N 天 */
    val daysLeft: Long
        get() {
            val zone = ZoneId.systemDefault()
            val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
            val targetStart = Instant.ofEpochMilli(targetDate).atZone(zone)
                .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            return (targetStart - todayStart) / 86_400_000L
        }
}
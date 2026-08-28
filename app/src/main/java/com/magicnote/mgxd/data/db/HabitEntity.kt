package com.magicnote.mgxd.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日打卡习惯实体
 * @param remindHour 每日提醒小时（-1 表示不提醒）
 * @param remindMinute 每日提醒分钟
 * @param targetDays 目标打卡天数（0 = 不限）
 * @param checkInDates 已打卡日期列表（"yyyy-MM-dd"，经 Converters JSON 存储）
 */
@Immutable
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val remindHour: Int = -1,
    val remindMinute: Int = 0,
    val targetDays: Int = 0,
    val checkInDates: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

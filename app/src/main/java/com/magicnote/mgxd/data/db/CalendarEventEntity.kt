package com.magicnote.mgxd.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日历日程事件实体
 * @param source 创建来源（"magic_ai"=由 Magic AI 创建，空串=手动创建）
 */
@Entity(tableName = "events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null,
    val color: Int = 0xFF7C4DFF.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "",
    /** 提前提醒分钟数（0=不提醒，5/10/15/30/60/120/1440 等） */
    val remindMinutes: Int = 0
)
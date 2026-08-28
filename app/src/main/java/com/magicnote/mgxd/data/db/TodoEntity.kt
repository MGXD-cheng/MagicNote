package com.magicnote.mgxd.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待办事项实体
 * @param dueTime 截止时间戳（毫秒），null 表示无截止
 * @param remindAt 提醒时间戳（毫秒），null 表示不提醒
 * @param remindScheduled 该提醒是否已由 AlarmManager 调度（用于开机恢复）
 * @param isLongTerm 是否为长期待办（true=长期，false=今日待办）
 * @param source 创建来源（"magic_ai"=由 Magic AI 创建，空串=手动创建）
 */
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val dueTime: Long? = null,
    val remindAt: Long? = null,
    val priority: Int = 1, // 0=低 1=中 2=高
    val completed: Boolean = false,
    val remindScheduled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val isLongTerm: Boolean = false,
    val source: String = ""
)
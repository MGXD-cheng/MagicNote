package com.magicnote.mgxd.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Magic AI 聊天历史实体（持久化保存，带时间戳）
 * @param role user / assistant
 * @param timestamp 消息时间（毫秒），用于历史时间标注
 */
@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
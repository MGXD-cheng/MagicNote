package com.magicnote.mgxd.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 日记实体
 * @param date 当天零点时间戳（同一天可有多篇，按 createdAt 排序合并展示）
 * @param createdAt 创建时刻（精确到时间，用于同一天多条按时间顺序）
 * @param mood 心情指数 0~4（😞😐🙂😄🤩）
 * @param imagePaths 本地图片绝对路径列表（保存在应用私有目录 diary_images/）
 */
@Immutable
@Entity(tableName = "diaries")
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val title: String? = null,
    val content: String,
    val mood: Int = 2,
    val imagePaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
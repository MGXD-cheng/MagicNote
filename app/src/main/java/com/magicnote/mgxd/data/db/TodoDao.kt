package com.magicnote.mgxd.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY completed ASC, dueTime IS NULL ASC, dueTime ASC, priority DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isLongTerm = 0 ORDER BY completed ASC, dueTime IS NULL ASC, dueTime ASC, priority DESC")
    fun observeToday(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isLongTerm = 1 ORDER BY completed ASC, dueTime IS NULL ASC, dueTime ASC, priority DESC")
    fun observeLongTerm(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE completed = 0 ORDER BY dueTime IS NULL ASC, dueTime ASC, priority DESC")
    fun observeActive(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getById(id: Long): TodoEntity?

    @Query("SELECT * FROM todos WHERE remindScheduled = 1")
    suspend fun getScheduledReminders(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(todo: TodoEntity): Long

    @Update
    suspend fun update(todo: TodoEntity)

    @Delete
    suspend fun delete(todo: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 删除指定时间之前创建、且已完成的今日待办（每日 0 点清理用；长期待办不清理） */
    @Query("DELETE FROM todos WHERE completed = 1 AND isLongTerm = 0 AND createdAt < :cutoff")
    suspend fun deleteCompletedBefore(cutoff: Long)

    /** 删除已过期（截止时间早于 cutoff）且已完成的待办（每日汇总时清理；长期待办不清理） */
    @Query("DELETE FROM todos WHERE completed = 1 AND isLongTerm = 0 AND dueTime IS NOT NULL AND dueTime < :cutoff")
    suspend fun deleteCompletedExpiredBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM todos WHERE completed = 0")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM todos WHERE completed = 1")
    suspend fun countCompleted(): Int
}
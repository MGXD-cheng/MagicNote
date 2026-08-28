package com.magicnote.mgxd.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {

    @Query("SELECT * FROM diaries ORDER BY date DESC, createdAt ASC")
    fun observeAll(): Flow<List<DiaryEntity>>

    @Query("SELECT * FROM diaries WHERE date = :dayStart ORDER BY createdAt ASC")
    fun observeByDay(dayStart: Long): Flow<List<DiaryEntity>>

    @Query("SELECT * FROM diaries WHERE date = :dayStart LIMIT 1")
    suspend fun getByDay(dayStart: Long): DiaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(diary: DiaryEntity): Long

    @Update
    suspend fun update(diary: DiaryEntity)

    @Delete
    suspend fun delete(diary: DiaryEntity)

    @Query("DELETE FROM diaries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
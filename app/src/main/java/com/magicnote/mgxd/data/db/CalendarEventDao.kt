package com.magicnote.mgxd.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {

    @Query("SELECT * FROM events WHERE startTime >= :start AND startTime < :end ORDER BY startTime ASC")
    fun observeBetween(start: Long, end: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM events ORDER BY startTime ASC")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): CalendarEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity): Long

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Delete
    suspend fun delete(event: CalendarEventEntity)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)
}
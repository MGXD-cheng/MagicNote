package com.magicnote.mgxd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CountdownDao {

    @Query("SELECT * FROM countdowns ORDER BY targetDate ASC, createdAt ASC")
    fun observeAll(): Flow<List<CountdownEntity>>

    @Query("SELECT * FROM countdowns WHERE id = :id")
    suspend fun getById(id: Long): CountdownEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(countdown: CountdownEntity): Long

    @Update
    suspend fun update(countdown: CountdownEntity)

    @Query("DELETE FROM countdowns WHERE id = :id")
    suspend fun deleteById(id: Long)
}
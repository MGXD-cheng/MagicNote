package com.magicnote.mgxd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC, id ASC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}
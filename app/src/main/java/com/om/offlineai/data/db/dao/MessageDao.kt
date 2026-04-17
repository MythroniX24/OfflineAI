package com.om.offlineai.data.db.dao

import androidx.room.*
import com.om.offlineai.data.db.entities.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun observeByConversation(convId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    suspend fun getByConversation(convId: Long): List<Message>

    // Sliding window: get last N messages for context
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(convId: Long, limit: Int): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: Long)

    // Full-text search across all messages
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' ORDER BY timestamp DESC")
    fun searchAll(q: String): Flow<List<Message>>
}

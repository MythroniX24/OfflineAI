package com.om.offlineai.data.db.dao

import androidx.room.*
import com.om.offlineai.data.db.entities.Memory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY isPinned DESC, isImportant DESC, usageCount DESC, createdAt DESC")
    fun observeAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<Memory>>

    @Query("SELECT * FROM memories WHERE isPinned = 1 OR isImportant = 1")
    suspend fun getHighPriority(): List<Memory>

    @Query("SELECT * FROM memories ORDER BY usageCount DESC LIMIT :limit")
    suspend fun getTopUsed(limit: Int = 10): List<Memory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Query("UPDATE memories SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Long)

    @Query("UPDATE memories SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)
}

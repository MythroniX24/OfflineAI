package com.om.offlineai.data.db.dao

import androidx.room.*
import com.om.offlineai.data.db.entities.KnowledgeItem
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_items ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<KnowledgeItem>>

    @Query("SELECT * FROM knowledge_items WHERE category = :cat ORDER BY updatedAt DESC")
    fun observeByCategory(cat: String): Flow<List<KnowledgeItem>>

    @Query("SELECT * FROM knowledge_items WHERE title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR tags LIKE '%' || :q || '%'")
    fun search(q: String): Flow<List<KnowledgeItem>>

    @Query("SELECT * FROM knowledge_items WHERE isPinned = 1")
    suspend fun getPinned(): List<KnowledgeItem>

    @Query("SELECT * FROM knowledge_items")
    suspend fun getAll(): List<KnowledgeItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: KnowledgeItem): Long

    @Update
    suspend fun update(item: KnowledgeItem)

    @Delete
    suspend fun delete(item: KnowledgeItem)

    @Query("DELETE FROM knowledge_items")
    suspend fun deleteAll()
}

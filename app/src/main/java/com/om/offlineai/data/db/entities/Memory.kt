package com.om.offlineai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val category: String = "general",   // "preference" | "fact" | "instruction" | "general"
    val isPinned: Boolean = false,
    val isImportant: Boolean = false,
    val isAutoSuggested: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0             // how often injected into prompt
)

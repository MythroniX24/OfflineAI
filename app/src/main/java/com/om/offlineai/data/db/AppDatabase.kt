package com.om.offlineai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.om.offlineai.data.db.dao.*
import com.om.offlineai.data.db.entities.*

@Database(
    entities = [
        Conversation::class,
        Message::class,
        Memory::class,
        KnowledgeItem::class,
        AppSettings::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun settingsDao(): SettingsDao
}

package com.om.offlineai.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,                     // singleton row
    val modelPath: String = "",
    val modelName: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val nThreads: Int = 0,                           // 0 = auto-detect
    val nCtx: Int = 2048,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val streamingEnabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val darkMode: Boolean = true,
    val hinglishMode: Boolean = false,
    val contextWindowSize: Int = 8,                  // messages to keep in context
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a helpful personal AI assistant named OfflineAI.
You run fully offline on the user's device.
Be concise, practical, and direct. Avoid unnecessary fluff.
Support Hinglish if the user writes in Hindi/Hinglish.
Always use stored memories and knowledge to personalize your responses.
For technical or physics topics, give accurate and structured answers.
For chess, suggest concrete moves and strategies.
For business tasks (Etsy, product listings, SEO), be creative and professional."""
    }
}

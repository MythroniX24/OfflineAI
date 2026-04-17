package com.om.offlineai.engine

import com.om.offlineai.data.db.entities.AppSettings
import com.om.offlineai.data.db.entities.KnowledgeItem
import com.om.offlineai.data.db.entities.Memory
import com.om.offlineai.data.db.entities.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PromptBuilder assembles the complete LLM prompt from:
 * 1. System prompt
 * 2. Pinned / important memories
 * 3. Relevant knowledge items
 * 4. Sliding window of recent messages
 * 5. Current user message
 *
 * Designed to keep total tokens under a configurable budget.
 */
@Singleton
class PromptBuilder @Inject constructor() {

    companion object {
        // Rough chars-per-token estimate for English/Hinglish text
        private const val CHARS_PER_TOKEN = 4
        // Reserve for model special tokens and safety margin
        private const val RESERVED_TOKENS = 128
    }

    /**
     * Build a complete prompt string.
     *
     * @param settings     AppSettings (system prompt, context size, etc.)
     * @param memories     All memories sorted by importance
     * @param knowledge    All knowledge items
     * @param history      Recent messages (will be trimmed to sliding window)
     * @param userMessage  Current user input
     */
    fun build(
        settings: AppSettings,
        memories: List<Memory>,
        knowledge: List<KnowledgeItem>,
        history: List<Message>,
        userMessage: String
    ): String {
        val tokenBudget = settings.nCtx - settings.maxTokens - RESERVED_TOKENS
        val charBudget = tokenBudget * CHARS_PER_TOKEN

        val sb = StringBuilder()

        // ── 1. System prompt ──────────────────────────────────────────────
        sb.appendLine("### System")
        sb.appendLine(settings.systemPrompt.trim())
        sb.appendLine()

        // ── 2. User memories (pinned + important first) ───────────────────
        val relevantMemories = memories
            .sortedWith(compareByDescending<Memory> { it.isPinned }
                .thenByDescending { it.isImportant }
                .thenByDescending { it.usageCount })
            .take(12)  // max 12 memories to avoid bloat

        if (relevantMemories.isNotEmpty()) {
            sb.appendLine("### User Preferences & Memory")
            relevantMemories.forEach { mem ->
                val prefix = when {
                    mem.isPinned    -> "📌 "
                    mem.isImportant -> "⭐ "
                    else            -> "• "
                }
                sb.appendLine("$prefix${mem.content.trim()}")
            }
            sb.appendLine()
        }

        // ── 3. Relevant knowledge items ───────────────────────────────────
        val pinnedKnowledge = knowledge
            .filter { it.isPinned }
            .take(5)

        if (pinnedKnowledge.isNotEmpty()) {
            sb.appendLine("### Knowledge Base")
            pinnedKnowledge.forEach { item ->
                sb.appendLine("**${item.title}**: ${item.content.take(300).trim()}")
            }
            sb.appendLine()
        }

        // ── 4. Sliding window chat history ────────────────────────────────
        sb.appendLine("### Conversation")
        val contextMessages = slidingWindow(history, charBudget - sb.length, userMessage)
        contextMessages.forEach { msg ->
            val role = when (msg.role) {
                "user"      -> "User"
                "assistant" -> "Assistant"
                else        -> "System"
            }
            sb.appendLine("$role: ${msg.content.trim()}")
        }

        // ── 5. Current user message ───────────────────────────────────────
        sb.appendLine("User: ${userMessage.trim()}")
        sb.append("Assistant:")  // model starts generating here

        return sb.toString()
    }

    /**
     * Trim history to fit remaining char budget.
     * Keeps the most recent messages and always preserves at least 2 exchanges.
     */
    private fun slidingWindow(
        history: List<Message>,
        charBudget: Int,
        userMessage: String
    ): List<Message> {
        var remaining = charBudget - userMessage.length
        val result = mutableListOf<Message>()

        // Iterate newest-first, then reverse
        for (msg in history.reversed()) {
            val cost = msg.content.length + 20  // +20 for role prefix
            if (remaining <= 0) break
            remaining -= cost
            result.add(0, msg)  // prepend to maintain order
        }

        return result
    }

    /**
     * Estimate token count for a string (rough approximation).
     */
    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN) + 1

    /**
     * Auto-suggest a memory from a user message if it looks like a personal fact.
     * Returns null if no suggestion.
     */
    fun suggestMemory(userMessage: String): String? {
        val lower = userMessage.lowercase()
        val memorablePatterns = listOf(
            "i prefer", "i like", "i hate", "i love", "i always",
            "i usually", "i work", "my business", "my shop", "i sell",
            "don't say", "please always", "remind me", "remember that",
            "mujhe pasand", "mera", "main hamesha"
        )
        return if (memorablePatterns.any { lower.contains(it) }) {
            userMessage.take(200)
        } else {
            null
        }
    }
}

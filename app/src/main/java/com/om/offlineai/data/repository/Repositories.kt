package com.om.offlineai.data.repository

import com.om.offlineai.data.db.dao.*
import com.om.offlineai.data.db.entities.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════
//  ChatRepository  —  conversations + messages
// ═══════════════════════════════════════════════════════════════════
@Singleton
class ChatRepository @Inject constructor(
    private val convDao: ConversationDao,
    private val msgDao: MessageDao
) {
    // Conversations
    fun observeConversations(): Flow<List<Conversation>> = convDao.observeAll()
    fun searchConversations(q: String): Flow<List<Conversation>> = convDao.search(q)
    suspend fun getConversation(id: Long): Conversation? = convDao.getById(id)
    suspend fun createConversation(title: String = "New Chat"): Long =
        convDao.insert(Conversation(title = title))
    suspend fun renameConversation(id: Long, title: String) = convDao.rename(id, title)
    suspend fun deleteConversation(conv: Conversation) = convDao.delete(conv)
    suspend fun deleteAllConversations() = convDao.deleteAll()
    suspend fun pinConversation(id: Long, pinned: Boolean) = convDao.setPinned(id, pinned)

    // Messages
    fun observeMessages(convId: Long): Flow<List<Message>> = msgDao.observeByConversation(convId)
    suspend fun getMessages(convId: Long): List<Message> = msgDao.getByConversation(convId)
    suspend fun getRecentMessages(convId: Long, limit: Int = 20): List<Message> =
        msgDao.getRecentMessages(convId, limit).reversed()

    suspend fun addMessage(message: Message): Long {
        val id = msgDao.insert(message)
        convDao.bumpUpdated(message.conversationId)
        return id
    }

    suspend fun deleteMessage(message: Message) = msgDao.delete(message)
    fun searchMessages(q: String): Flow<List<Message>> = msgDao.searchAll(q)

    // Auto-title: use first user message (trimmed to 40 chars)
    suspend fun autoTitle(convId: Long) {
        val first = msgDao.getByConversation(convId)
            .firstOrNull { it.role == "user" } ?: return
        val title = first.content.take(40).let { if (it.length == 40) "$it…" else it }
        convDao.rename(convId, title)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  MemoryRepository
// ═══════════════════════════════════════════════════════════════════
@Singleton
class MemoryRepository @Inject constructor(private val dao: MemoryDao) {
    fun observeAll(): Flow<List<Memory>> = dao.observeAll()
    fun search(q: String): Flow<List<Memory>> = dao.search(q)
    suspend fun getHighPriority(): List<Memory> = dao.getHighPriority()
    suspend fun getTopUsed(n: Int = 10): List<Memory> = dao.getTopUsed(n)

    suspend fun add(content: String, category: String = "general", important: Boolean = false): Long =
        dao.insert(Memory(content = content, category = category, isImportant = important))

    suspend fun update(memory: Memory) = dao.update(memory.copy(updatedAt = System.currentTimeMillis()))
    suspend fun delete(memory: Memory) = dao.delete(memory)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun incrementUsage(id: Long) = dao.incrementUsage(id)

    /** Pre-seed default user memories for Om */
    suspend fun seedDefaultMemories() {
        val defaults = listOf(
            Memory(content = "User's name is Om", category = "fact", isImportant = true, isPinned = true),
            Memory(content = "Om is a student interested in physics, chess, and computer technology", category = "fact", isImportant = true),
            Memory(content = "Om runs a jewellery business on Etsy, mainly sterling silver with gold plating rings", category = "business", isImportant = true, isPinned = true),
            Memory(content = "Om prefers practical, direct answers — no unnecessary fluff", category = "preference", isImportant = true),
            Memory(content = "Om prefers Hinglish communication (Hindi + English mix)", category = "preference"),
            Memory(content = "Om prefers free or low-cost solutions", category = "preference"),
            Memory(content = "Om values clean, modern UI design", category = "preference"),
            Memory(content = "Om wants AI tools that are efficient and optimized", category = "preference"),
        )
        defaults.forEach { dao.insert(it) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  KnowledgeRepository
// ═══════════════════════════════════════════════════════════════════
@Singleton
class KnowledgeRepository @Inject constructor(private val dao: KnowledgeDao) {
    fun observeAll(): Flow<List<KnowledgeItem>> = dao.observeAll()
    fun observeByCategory(cat: String): Flow<List<KnowledgeItem>> = dao.observeByCategory(cat)
    fun search(q: String): Flow<List<KnowledgeItem>> = dao.search(q)
    suspend fun getPinned(): List<KnowledgeItem> = dao.getPinned()
    suspend fun getAll(): List<KnowledgeItem> = dao.getAll()

    suspend fun add(title: String, content: String, category: String = "general", tags: String = ""): Long =
        dao.insert(KnowledgeItem(title = title, content = content, category = category, tags = tags))

    suspend fun update(item: KnowledgeItem) = dao.update(item.copy(updatedAt = System.currentTimeMillis()))
    suspend fun delete(item: KnowledgeItem) = dao.delete(item)
    suspend fun deleteAll() = dao.deleteAll()

    /** Pre-seed Etsy business knowledge for Om */
    suspend fun seedDefaultKnowledge() {
        val items = listOf(
            KnowledgeItem(
                title = "Etsy Product Title Formula",
                content = "Format: [Material] [Product Type] | [Style/Design] | [Occasion] | [Gift idea]\nExample: Sterling Silver Ring | Minimalist Band | Adjustable Gold Plated | Gift for Her",
                category = "business", isPinned = true
            ),
            KnowledgeItem(
                title = "Etsy SEO Keywords",
                content = "sterling silver ring, gold plated ring, minimalist ring, adjustable band ring, gift for her, dainty ring, boho jewelry, stacking ring, handmade ring, silver band",
                category = "business", isPinned = true
            ),
            KnowledgeItem(
                title = "Product Description Template",
                content = "Start with the vibe/feeling. Describe material and finish. List key features (adjustable, hypoallergenic, etc.). Add care instructions. End with gift context.",
                category = "template"
            ),
            KnowledgeItem(
                title = "Micron Report Format",
                content = "Gold plating micron report format: Product: [name], Base Metal: Sterling Silver, Plating: 18K Gold, Thickness: [X] microns, Method: Electroplating, Quality: ASTM B488",
                category = "business"
            ),
        )
        items.forEach { dao.insert(it) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SettingsRepository
// ═══════════════════════════════════════════════════════════════════
@Singleton
class SettingsRepository @Inject constructor(private val dao: SettingsDao) {
    fun observe(): Flow<AppSettings?> = dao.observe()
    suspend fun get(): AppSettings = dao.get() ?: AppSettings()
    suspend fun save(settings: AppSettings) = dao.save(settings)
    suspend fun updateModel(path: String, name: String) = dao.updateModel(path, name)

    suspend fun ensureDefaults() {
        if (dao.get() == null) dao.save(AppSettings())
    }
}

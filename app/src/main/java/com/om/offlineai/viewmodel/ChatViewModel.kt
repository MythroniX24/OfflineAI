package com.om.offlineai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.om.offlineai.data.db.entities.Message
import com.om.offlineai.data.repository.ChatRepository
import com.om.offlineai.data.repository.MemoryRepository
import com.om.offlineai.data.repository.KnowledgeRepository
import com.om.offlineai.data.repository.SettingsRepository
import com.om.offlineai.engine.LlamaEngine
import com.om.offlineai.engine.PromptBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val error: String? = null,
    val suggestedMemory: String? = null,
    val currentConvId: Long = -1L
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepo: ChatRepository,
    private val memoryRepo: MemoryRepository,
    private val knowledgeRepo: KnowledgeRepository,
    private val settingsRepo: SettingsRepository,
    private val engine: LlamaEngine,
    private val promptBuilder: PromptBuilder
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageCollectorJob: Job? = null
    private var inferJob: Job? = null

    // ── Conversation management ──────────────────────────────────────────────

    fun openConversation(convId: Long) {
        _uiState.update { it.copy(currentConvId = convId) }
        messageCollectorJob?.cancel()
        messageCollectorJob = viewModelScope.launch {
            chatRepo.observeMessages(convId).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    suspend fun createNewConversation(): Long {
        val id = chatRepo.createConversation()
        openConversation(id)
        return id
    }

    // ── Message sending ──────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return
        val convId = _uiState.value.currentConvId
        if (convId < 0) return

        inferJob?.cancel()
        inferJob = viewModelScope.launch {
            // 1. Save user message
            val userMsg = Message(conversationId = convId, role = "user", content = text.trim())
            chatRepo.addMessage(userMsg)

            // Auto-title on first message
            if (_uiState.value.messages.size <= 1) {
                chatRepo.autoTitle(convId)
            }

            // 2. Check auto-memory suggestion
            val settings = settingsRepo.get()
            if (settings.autoMemoryEnabled) {
                val suggestion = promptBuilder.suggestMemory(text)
                if (suggestion != null) {
                    _uiState.update { it.copy(suggestedMemory = suggestion) }
                }
            }

            // 3. Build prompt
            val memories  = memoryRepo.getHighPriority() + memoryRepo.getTopUsed(8)
            val knowledge = knowledgeRepo.getPinned()
            val history   = chatRepo.getRecentMessages(convId, settings.contextWindowSize * 2)
            val prompt    = promptBuilder.build(settings, memories.distinct(), knowledge, history, text)

            // 4. Stream inference
            _uiState.update { it.copy(isGenerating = true, streamingText = "", error = null) }
            val fullResponse = StringBuilder()

            try {
                engine.infer(prompt, settings.maxTokens)
                    .collect { token ->
                        fullResponse.append(token)
                        _uiState.update { it.copy(streamingText = fullResponse.toString()) }
                    }

                // 5. Save completed assistant message
                val assistantMsg = Message(
                    conversationId = convId,
                    role = "assistant",
                    content = fullResponse.toString().trim()
                )
                chatRepo.addMessage(assistantMsg)
                _uiState.update { it.copy(streamingText = "", isGenerating = false) }

            } catch (e: CancellationException) {
                // User stopped — save partial response if any
                if (fullResponse.isNotBlank()) {
                    chatRepo.addMessage(
                        Message(conversationId = convId, role = "assistant",
                            content = fullResponse.toString().trim() + " [stopped]")
                    )
                }
                _uiState.update { it.copy(streamingText = "", isGenerating = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isGenerating = false, streamingText = "",
                        error = "Inference error: ${e.message}")
                }
            }
        }
    }

    fun stopGeneration() {
        engine.stopInference()
        inferJob?.cancel()
        _uiState.update { it.copy(isGenerating = false) }
    }

    // ── Memory suggestion ────────────────────────────────────────────────────

    fun acceptMemorySuggestion() {
        val suggestion = _uiState.value.suggestedMemory ?: return
        viewModelScope.launch {
            memoryRepo.add(suggestion, category = "preference")
            _uiState.update { it.copy(suggestedMemory = null) }
        }
    }

    fun dismissMemorySuggestion() {
        _uiState.update { it.copy(suggestedMemory = null) }
    }

    // ── Misc ─────────────────────────────────────────────────────────────────

    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        messageCollectorJob?.cancel()
        inferJob?.cancel()
    }
}

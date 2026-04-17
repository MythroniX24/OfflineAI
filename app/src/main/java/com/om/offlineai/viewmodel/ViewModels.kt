package com.om.offlineai.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.om.offlineai.data.db.entities.*
import com.om.offlineai.data.repository.*
import com.om.offlineai.engine.LlamaEngine
import com.om.offlineai.engine.ModelState
import com.om.offlineai.util.DeviceCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════
//  ModelViewModel — model import, loading, status
// ═══════════════════════════════════════════════════════════════════
data class ModelUiState(
    val state: ModelState = ModelState.Unloaded,
    val modelPath: String = "",
    val modelName: String = "",
    val modelSizeMB: Long = 0L,
    val modelInfo: String = "",
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val error: String? = null,
    val deviceWarning: String? = null
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LlamaEngine,
    private val settingsRepo: SettingsRepository,
    private val deviceCapability: DeviceCapability,
    private val memoryRepo: MemoryRepository,
    private val knowledgeRepo: KnowledgeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init {
        engine.init()
        viewModelScope.launch {
            val settings = settingsRepo.get()
            if (settings.modelPath.isNotBlank()) {
                val f = File(settings.modelPath)
                if (f.exists()) {
                    _uiState.update { it.copy(modelPath = settings.modelPath, modelName = settings.modelName) }
                    loadModel(settings.modelPath)
                }
            }
        }
    }

    /** Import GGUF from a content URI (file picker result) */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null, importProgress = 0f) }
            try {
                val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
                val fileName  = resolveFileName(uri) ?: "model.gguf"
                val destFile  = File(modelsDir, fileName)

                // Check device RAM before copying
                val sizeMB = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize / (1024 * 1024)
                } ?: 0L

                if (!deviceCapability.canLoadModel(sizeMB)) {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            deviceWarning = "⚠️ Model is ${sizeMB}MB but only ${deviceCapability.profile().ramMB}MB RAM is available. The app may crash. Try a smaller quantized model (Q4_0 or Q2_K)."
                        )
                    }
                    return@launch
                }

                // Copy file with progress
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(8192)
                        val total = sizeMB * 1024 * 1024
                        var copied = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            copied += n
                            if (total > 0) _uiState.update {
                                it.copy(importProgress = copied.toFloat() / total)
                            }
                        }
                    }
                } ?: throw Exception("Cannot open file")

                _uiState.update { it.copy(isImporting = false, importProgress = 1f) }
                loadModel(destFile.absolutePath)

            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, error = "Import failed: ${e.message}") }
            }
        }
    }

    fun loadModel(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(state = ModelState.Loading) }
            val success = engine.loadModel(path)
            val f = File(path)
            if (success) {
                val info = engine.getModelInfo()
                settingsRepo.updateModel(path, f.name)
                _uiState.update {
                    it.copy(
                        state = engine.modelState,
                        modelPath = path,
                        modelName = f.name,
                        modelSizeMB = f.length() / (1024 * 1024),
                        modelInfo = info
                    )
                }
                // Seed defaults on first load
                seedDefaultsIfNeeded()
            } else {
                _uiState.update { it.copy(state = engine.modelState, error = "Failed to load model") }
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            engine.freeModel()
            val path = _uiState.value.modelPath
            if (path.isNotBlank()) File(path).delete()
            settingsRepo.updateModel("", "")
            _uiState.update { ModelUiState() }
        }
    }

    fun reloadModel() {
        val path = _uiState.value.modelPath
        if (path.isNotBlank()) loadModel(path)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearWarning() = _uiState.update { it.copy(deviceWarning = null) }

    private fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private suspend fun seedDefaultsIfNeeded() {
        val settings = settingsRepo.get()
        // Only seed once (check if memories already exist)
        val existing = memoryRepo.getHighPriority()
        if (existing.isEmpty()) {
            memoryRepo.seedDefaultMemories()
            knowledgeRepo.seedDefaultKnowledge()
        }
    }

    override fun onCleared() { super.onCleared(); engine.destroy() }
}

// ═══════════════════════════════════════════════════════════════════
//  ConversationListViewModel
// ═══════════════════════════════════════════════════════════════════
@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val chatRepo: ChatRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val conversations = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) chatRepo.observeConversations()
            else chatRepo.searchConversations(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun deleteConversation(conv: Conversation) = viewModelScope.launch { chatRepo.deleteConversation(conv) }
    fun pinConversation(id: Long, pinned: Boolean) = viewModelScope.launch { chatRepo.pinConversation(id, pinned) }
    fun renameConversation(id: Long, title: String) = viewModelScope.launch { chatRepo.renameConversation(id, title) }
    fun deleteAll() = viewModelScope.launch { chatRepo.deleteAllConversations() }
}

// ═══════════════════════════════════════════════════════════════════
//  MemoryViewModel
// ═══════════════════════════════════════════════════════════════════
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repo: MemoryRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val memories = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) repo.observeAll() else repo.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun add(content: String, category: String = "general") = viewModelScope.launch { repo.add(content, category) }
    fun update(memory: Memory) = viewModelScope.launch { repo.update(memory) }
    fun delete(memory: Memory) = viewModelScope.launch { repo.delete(memory) }
    fun deleteAll() = viewModelScope.launch { repo.deleteAll() }
    fun setPinned(memory: Memory, pinned: Boolean) = viewModelScope.launch { repo.update(memory.copy(isPinned = pinned)) }
    fun setImportant(memory: Memory, important: Boolean) = viewModelScope.launch { repo.update(memory.copy(isImportant = important)) }
}

// ═══════════════════════════════════════════════════════════════════
//  KnowledgeViewModel
// ═══════════════════════════════════════════════════════════════════
@HiltViewModel
class KnowledgeViewModel @Inject constructor(
    private val repo: KnowledgeRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val items = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) repo.observeAll() else repo.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun add(title: String, content: String, category: String = "general", tags: String = "") =
        viewModelScope.launch { repo.add(title, content, category, tags) }
    fun update(item: KnowledgeItem) = viewModelScope.launch { repo.update(item) }
    fun delete(item: KnowledgeItem) = viewModelScope.launch { repo.delete(item) }
    fun deleteAll() = viewModelScope.launch { repo.deleteAll() }
    fun setPinned(item: KnowledgeItem, pinned: Boolean) = viewModelScope.launch { repo.update(item.copy(isPinned = pinned)) }
}

// ═══════════════════════════════════════════════════════════════════
//  SettingsViewModel
// ═══════════════════════════════════════════════════════════════════
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val chatRepo: ChatRepository,
    private val memoryRepo: MemoryRepository,
    private val knowledgeRepo: KnowledgeRepository,
    private val deviceCapability: DeviceCapability
) : ViewModel() {

    val settings = settingsRepo.observe()
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val deviceInfo = deviceCapability.deviceSummary()

    fun save(s: AppSettings) = viewModelScope.launch { settingsRepo.save(s) }

    fun clearAllChats()     = viewModelScope.launch { chatRepo.deleteAllConversations() }
    fun clearAllMemory()    = viewModelScope.launch { memoryRepo.deleteAll() }
    fun clearAllKnowledge() = viewModelScope.launch { knowledgeRepo.deleteAll() }

    fun resetAll() = viewModelScope.launch {
        chatRepo.deleteAllConversations()
        memoryRepo.deleteAll()
        knowledgeRepo.deleteAll()
        settingsRepo.save(AppSettings())
    }
}

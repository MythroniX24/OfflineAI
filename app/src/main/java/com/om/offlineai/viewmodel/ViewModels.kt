package com.om.offlineai.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.om.offlineai.data.db.entities.*
import com.om.offlineai.data.repository.*
import com.om.offlineai.engine.LlamaEngine
import com.om.offlineai.engine.ModelState
import com.om.offlineai.util.DeviceCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

// ── Downloadable model catalog ────────────────────────────────────────────────
data class DownloadableModel(
    val name: String,
    val description: String,
    val sizeMB: Int,
    val url: String,
    val fileName: String,
    val recommended: Boolean = false
)

val DOWNLOADABLE_MODELS = listOf(
    DownloadableModel(
        name = "TinyLlama 1.1B Chat",
        description = "Sabse chhota aur fast. Basic chat ke liye.",
        sizeMB = 670,
        url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_0.gguf",
        fileName = "tinyllama-1.1b-chat-q4_0.gguf",
        recommended = true
    ),
    DownloadableModel(
        name = "Qwen2 1.5B",
        description = "Hindi/Hinglish ke liye best. Chhota aur efficient.",
        sizeMB = 940,
        url = "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_k_m.gguf",
        fileName = "qwen2-1_5b-instruct-q4_k_m.gguf"
    ),
    DownloadableModel(
        name = "Phi-2 (2.7B)",
        description = "Microsoft ka model. Zyada smart, thoda bada.",
        sizeMB = 1600,
        url = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
        fileName = "phi-2-q4_k_m.gguf"
    ),
    DownloadableModel(
        name = "Gemma 2B",
        description = "Google ka model. Balanced quality.",
        sizeMB = 1500,
        url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
        fileName = "gemma-2-2b-it-q4_k_m.gguf"
    )
)

// ═══════════════════════════════════════════════════════════════════
//  ModelViewModel
// ═══════════════════════════════════════════════════════════════════
data class ModelUiState(
    val state: ModelState = ModelState.Unloaded,
    val modelPath: String = "",
    val modelName: String = "",
    val modelSizeMB: Long = 0L,
    val modelInfo: String = "",
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadingModel: String = "",
    val downloadedMB: Int = 0,
    val totalMB: Int = 0,
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

    // ── Download helper ───────────────────────────────────────────────────────
    private val downloader = ModelDownloadHelper(
        context   = context,
        uiState   = _uiState,
        onComplete = { path -> loadModel(path) },
        scope     = viewModelScope
    )

    init {
        engine.init()
        viewModelScope.launch {
            val settings = settingsRepo.get()
            if (settings.modelPath.isNotBlank() && File(settings.modelPath).exists()) {
                _uiState.update {
                    it.copy(modelPath = settings.modelPath, modelName = settings.modelName)
                }
                // Don't auto-load if a download is already in progress
                if (!_uiState.value.isDownloading) {
                    loadModel(settings.modelPath)
                }
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────
    fun downloadModel(model: DownloadableModel) = downloader.startDownload(model)
    fun cancelDownload() = downloader.cancel()

    // ── Import from file picker ───────────────────────────────────────────────
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, error = null, importProgress = 0f) }
            try {
                val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir
                destDir.mkdirs()
                val fileName  = resolveFileName(uri) ?: "model.gguf"
                val destFile  = File(destDir, fileName)

                val sizeMB = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    it.statSize / (1024 * 1024)
                } ?: 0L

                if (!deviceCapability.canLoadModel(sizeMB)) {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            deviceWarning = "⚠️ Model ${sizeMB}MB — only ${deviceCapability.profile().ramMB}MB RAM. May crash."
                        )
                    }
                    return@launch
                }

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buf = ByteArray(8192)
                        val total = sizeMB * 1024 * 1024
                        var copied = 0L; var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n); copied += n
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

    // ── Load model ────────────────────────────────────────────────────────────
    fun loadModel(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(state = ModelState.Loading) }
            val success = engine.loadModel(path)
            if (success) {
                val f = File(path)
                settingsRepo.updateModel(path, f.name)
                _uiState.update {
                    it.copy(
                        state     = engine.modelState,
                        modelPath = path,
                        modelName = f.name,
                        modelSizeMB = f.length() / (1024 * 1024),
                        modelInfo = engine.getModelInfo()
                    )
                }
                seedDefaultsIfNeeded()
            } else {
                _uiState.update { it.copy(state = engine.modelState, error = "Model load nahi hua") }
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

    fun clearError()   = _uiState.update { it.copy(error = null) }
    fun clearWarning() = _uiState.update { it.copy(deviceWarning = null) }

    private fun resolveFileName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private suspend fun seedDefaultsIfNeeded() {
        if (memoryRepo.getHighPriority().isEmpty()) {
            memoryRepo.seedDefaultMemories()
            knowledgeRepo.seedDefaultKnowledge()
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloader.destroy()
        engine.destroy()
    }
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
class MemoryViewModel @Inject constructor(private val repo: MemoryRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val memories = _searchQuery
        .debounce(300)
        .flatMapLatest { q -> if (q.isBlank()) repo.observeAll() else repo.search(q) }
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
class KnowledgeViewModel @Inject constructor(private val repo: KnowledgeRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val items = _searchQuery
        .debounce(300)
        .flatMapLatest { q -> if (q.isBlank()) repo.observeAll() else repo.search(q) }
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
    fun clearAllChats() = viewModelScope.launch { chatRepo.deleteAllConversations() }
    fun clearAllMemory() = viewModelScope.launch { memoryRepo.deleteAll() }
    fun clearAllKnowledge() = viewModelScope.launch { knowledgeRepo.deleteAll() }
    fun resetAll() = viewModelScope.launch {
        chatRepo.deleteAllConversations()
        memoryRepo.deleteAll()
        knowledgeRepo.deleteAll()
        settingsRepo.save(AppSettings())
    }
}

package com.om.offlineai.viewmodel

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

private const val TAG = "ModelDownload"
private const val PREFS_NAME = "model_download_prefs"
private const val KEY_DOWNLOAD_ID    = "active_download_id"
private const val KEY_DOWNLOAD_MODEL = "active_download_model"
private const val KEY_DEST_PATH      = "active_dest_path"

// Minimum valid GGUF file size (100 MB) — anything smaller is garbage/HTML
private const val MIN_VALID_SIZE_BYTES = 100L * 1024 * 1024

class ModelDownloadHelper(
    private val context: Context,
    private val uiState: MutableStateFlow<ModelUiState>,
    private val onComplete: (filePath: String) -> Unit,
    private val scope: CoroutineScope
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var progressJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val saved = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
            if (id == saved && id != -1L) handleDownloadComplete(id)
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        reattachPendingDownload()
    }

    fun startDownload(model: DownloadableModel) {
        if (uiState.value.isDownloading) return

        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        destDir.mkdirs()
        val destFile = File(destDir, model.fileName)

        // Already downloaded and valid?
        if (destFile.exists() && destFile.length() >= MIN_VALID_SIZE_BYTES) {
            Log.i(TAG, "Model already valid at ${destFile.path} (${destFile.length()} bytes)")
            scope.launch { onComplete(destFile.absolutePath) }
            return
        }

        // Remove partial/invalid file
        if (destFile.exists()) {
            Log.w(TAG, "Removing invalid file (${destFile.length()} bytes) at ${destFile.path}")
            destFile.delete()
        }

        uiState.update {
            it.copy(
                isDownloading = true,
                downloadProgress = 0f,
                downloadingModel = model.name,
                totalMB = model.sizeMB,
                downloadedMB = 0,
                error = null
            )
        }

        // ── CRITICAL FIX: append ?download=true for HuggingFace URLs ──────────
        val downloadUrl = if (model.url.contains("huggingface.co") &&
                             !model.url.contains("?download=true")) {
            "${model.url}?download=true"
        } else model.url

        Log.i(TAG, "Starting download: $downloadUrl -> ${destFile.path}")

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle(model.name)
            .setDescription("OfflineAI — downloading ${model.name}")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = dm.enqueue(request)
        Log.i(TAG, "Enqueued download id=$downloadId")

        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_MODEL, model.name)
            .putString(KEY_DEST_PATH, destFile.absolutePath)
            .apply()

        startProgressPolling(downloadId)
    }

    fun cancel() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id >= 0) dm.remove(id)
        clearPersistedDownload()
        progressJob?.cancel()
        uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
    }

    private fun reattachPendingDownload() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val modelName = prefs.getString(KEY_DOWNLOAD_MODEL, "") ?: ""
        if (id < 0 || modelName.isBlank()) return

        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query) ?: run { clearPersistedDownload(); return }
        if (!cursor.moveToFirst()) { cursor.close(); clearPersistedDownload(); return }

        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        cursor.close()

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> handleDownloadComplete(id)
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                uiState.update {
                    it.copy(isDownloading = true, downloadingModel = modelName, downloadProgress = 0f)
                }
                startProgressPolling(id)
            }
            else -> clearPersistedDownload()
        }
    }

    private fun startProgressPolling(downloadId: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && uiState.value.isDownloading) {
                queryProgress(downloadId)
                delay(1000)
            }
        }
    }

    private fun queryProgress(downloadId: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = dm.query(query) ?: return
        if (!cursor.moveToFirst()) { cursor.close(); return }

        val status     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total      = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        cursor.close()

        when (status) {
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                uiState.update {
                    it.copy(
                        downloadProgress = progress,
                        downloadedMB     = (downloaded / (1024 * 1024)).toInt(),
                        totalMB          = if (total > 0) (total / (1024 * 1024)).toInt() else it.totalMB
                    )
                }
            }
            DownloadManager.STATUS_SUCCESSFUL -> handleDownloadComplete(downloadId)
            DownloadManager.STATUS_FAILED -> {
                progressJob?.cancel()
                clearPersistedDownload()
                uiState.update {
                    it.copy(isDownloading = false,
                        error = "Download failed. Internet check karo ya doosra model try karo.")
                }
            }
        }
    }

    private fun handleDownloadComplete(downloadId: Long) {
        progressJob?.cancel()

        // ── Use the saved dest path — most reliable approach ─────────────────
        val savedPath = prefs.getString(KEY_DEST_PATH, null)
        clearPersistedDownload()

        val filePath: String? = savedPath ?: run {
            // Fallback: query DownloadManager for local URI
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = dm.query(query) ?: return
            if (!cursor.moveToFirst()) { cursor.close(); return }
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            cursor.close()
            uri?.let { Uri.parse(it).path }
        }

        if (filePath == null) {
            uiState.update { it.copy(isDownloading = false, error = "Downloaded file path not found") }
            return
        }

        val file = File(filePath)
        Log.i(TAG, "Download complete: $filePath (${file.length()} bytes)")

        // ── Validate file size — catch HTML error pages ───────────────────────
        if (!file.exists()) {
            uiState.update { it.copy(isDownloading = false,
                error = "Downloaded file missing at $filePath") }
            return
        }

        if (file.length() < MIN_VALID_SIZE_BYTES) {
            val sizeMB = file.length() / (1024 * 1024)
            file.delete() // remove garbage file
            uiState.update {
                it.copy(
                    isDownloading = false,
                    error = "Download incomplete (${sizeMB}MB). HuggingFace se direct download karne ki koshish ki thi — seedha 'Doosra model' try karo ya WiFi use karo."
                )
            }
            return
        }

        Log.i(TAG, "File valid (${file.length() / (1024*1024)}MB), loading model...")
        uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
        scope.launch { onComplete(filePath) }
    }

    private fun clearPersistedDownload() {
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_MODEL)
            .remove(KEY_DEST_PATH)
            .apply()
    }

    fun destroy() {
        progressJob?.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

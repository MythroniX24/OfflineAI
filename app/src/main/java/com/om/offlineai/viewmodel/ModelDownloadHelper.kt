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
private const val KEY_DOWNLOAD_ID = "active_download_id"
private const val KEY_DOWNLOAD_MODEL = "active_download_model"

/**
 * Handles model download via Android DownloadManager.
 * DownloadManager is a system service — download continues even if app is killed.
 * On relaunch, we re-attach to the pending download using the saved download ID.
 */
class ModelDownloadHelper(
    private val context: Context,
    private val uiState: MutableStateFlow<ModelUiState>,
    private val onComplete: (filePath: String) -> Unit,
    private val scope: CoroutineScope
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var progressJob: Job? = null

    // BroadcastReceiver — fires when DownloadManager finishes (works even in background)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val saved = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
            if (id == saved && id != -1L) {
                Log.i(TAG, "Download complete broadcast received for id=$id")
                handleDownloadComplete(id)
            }
        }
    }

    init {
        // Register receiver — EXPORTED so system DownloadManager can reach us
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Re-attach to any in-progress download from previous session
        reattachPendingDownload()
    }

    // ── Start download ────────────────────────────────────────────────────────
    fun startDownload(model: DownloadableModel) {
        if (uiState.value.isDownloading) return

        // Store in External Files (app-private, no FileProvider needed, survives app kill)
        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        destDir.mkdirs()
        val destFile = File(destDir, model.fileName)

        // Already fully downloaded?
        if (destFile.exists() && destFile.length() > 100_000L) {
            Log.i(TAG, "Model already exists at ${destFile.path}")
            onComplete(destFile.absolutePath)
            return
        }

        // Delete partial file if exists
        if (destFile.exists()) destFile.delete()

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

        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.name)
            .setDescription("OfflineAI — downloading ${model.name}")
            // Show notification while downloading AND after completion
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // setDestinationInExternalFilesDir — no FileProvider, no FileUriException
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = dm.enqueue(request)
        Log.i(TAG, "Download started id=$downloadId -> ${destFile.path}")

        // Persist download ID so we can re-attach after app restart
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_MODEL, model.name)
            .apply()

        startProgressPolling(downloadId)
    }

    // ── Cancel ────────────────────────────────────────────────────────────────
    fun cancel() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id >= 0) dm.remove(id)
        clearPersistedDownload()
        progressJob?.cancel()
        uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
    }

    // ── Re-attach to download after app restart ───────────────────────────────
    private fun reattachPendingDownload() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val modelName = prefs.getString(KEY_DOWNLOAD_MODEL, "") ?: ""
        if (id < 0 || modelName.isBlank()) return

        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query) ?: return
        if (!cursor.moveToFirst()) { cursor.close(); clearPersistedDownload(); return }

        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        cursor.close()

        Log.i(TAG, "Reattaching to download id=$id status=$status")

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                // Finished while app was closed
                handleDownloadComplete(id)
            }
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                // Still in progress — show progress bar
                uiState.update {
                    it.copy(
                        isDownloading = true,
                        downloadingModel = modelName,
                        downloadProgress = 0f
                    )
                }
                startProgressPolling(id)
            }
            else -> clearPersistedDownload()
        }
    }

    // ── Poll progress every second ────────────────────────────────────────────
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

        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(
            DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        cursor.close()

        when (status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                uiState.update {
                    it.copy(
                        downloadProgress = progress,
                        downloadedMB = (downloaded / (1024 * 1024)).toInt(),
                        totalMB = if (total > 0) (total / (1024 * 1024)).toInt()
                                  else it.totalMB
                    )
                }
            }
            DownloadManager.STATUS_SUCCESSFUL -> handleDownloadComplete(downloadId)
            DownloadManager.STATUS_FAILED -> {
                progressJob?.cancel()
                clearPersistedDownload()
                uiState.update {
                    it.copy(isDownloading = false,
                        error = "Download failed. Internet check karo.")
                }
            }
        }
    }

    private fun handleDownloadComplete(downloadId: Long) {
        progressJob?.cancel()

        // Get file path from DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query) ?: run {
            clearPersistedDownload()
            return
        }
        if (!cursor.moveToFirst()) { cursor.close(); clearPersistedDownload(); return }

        val localUri = cursor.getString(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        cursor.close()
        clearPersistedDownload()

        if (localUri == null) {
            uiState.update { it.copy(isDownloading = false, error = "Downloaded file not found") }
            return
        }

        // Convert URI → file path safely
        val filePath = try {
            Uri.parse(localUri).path ?: localUri.removePrefix("file://")
        } catch (e: Exception) { localUri.removePrefix("file://") }

        Log.i(TAG, "Download complete, file at: $filePath")
        uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
        scope.launch { onComplete(filePath) }
    }

    private fun clearPersistedDownload() {
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_MODEL).apply()
    }

    fun destroy() {
        progressJob?.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

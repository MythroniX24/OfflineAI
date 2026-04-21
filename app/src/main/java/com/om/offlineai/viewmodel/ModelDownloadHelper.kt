package com.om.offlineai.viewmodel

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG  = "ModelDownload"
private const val PREFS = "model_dl_v4"
private const val KEY_ID       = "dl_id"
private const val KEY_NAME     = "dl_name"
private const val KEY_FILENAME = "dl_filename"

/**
 * Download strategy:
 *   1. DownloadManager saves to app-private EXTERNAL dir (it has write access there)
 *   2. After success, we MOVE file to app's internal filesDir/models/
 *      (renameTo = instant if same filesystem, fallback to stream copy)
 *   3. Load from internal path — JNI always has read access here
 *
 * Why internal storage for loading:
 *   - JNI/NDK code can always read from getFilesDir()
 *   - External storage can sometimes be unavailable or have mmap restrictions
 *   - No storage permissions needed
 */
class ModelDownloadHelper(
    private val context: Context,
    private val uiState: MutableStateFlow<ModelUiState>,
    private val onComplete: (filePath: String) -> Unit,
    private val scope: CoroutineScope
) {
    private val dm    = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pollJob: Job? = null

    // Destination directories
    private fun externalDir()  = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                                    ?: context.filesDir
    private fun internalDir()  = File(context.filesDir, "models").also { it.mkdirs() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id    = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val saved = prefs.getLong(KEY_ID, -1L)
            if (id == saved && id != -1L) scope.launch { handleComplete(id) }
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        reattach()
    }

    fun startDownload(model: DownloadableModel) {
        if (uiState.value.isDownloading) return

        // Check if already in internal storage
        val internalFile = File(internalDir(), model.fileName)
        if (internalFile.exists() && internalFile.length() > 50_000_000L) {
            Log.i(TAG, "Already in internal: ${internalFile.path} (${internalFile.length()/1024/1024}MB)")
            scope.launch { onComplete(internalFile.absolutePath) }
            return
        }

        // Clean up any existing partial files
        internalFile.delete()
        File(externalDir(), model.fileName).delete()

        uiState.update {
            it.copy(isDownloading = true, downloadProgress = 0f,
                downloadingModel = model.name, totalMB = model.sizeMB,
                downloadedMB = 0, error = null)
        }

        // Add ?download=true for HuggingFace direct binary download
        val url = model.url.let {
            if (it.contains("huggingface.co") && !it.contains("?download=true"))
                "$it?download=true" else it
        }

        Log.i(TAG, "Downloading: $url -> ${externalDir()}/${model.fileName}")

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(model.name)
            .setDescription("OfflineAI — ${model.sizeMB}MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(req)
        Log.i(TAG, "Enqueued id=$id")

        prefs.edit()
            .putLong(KEY_ID, id)
            .putString(KEY_NAME, model.name)
            .putString(KEY_FILENAME, model.fileName)
            .apply()

        startPolling(id)
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) dm.remove(id)
        clearPrefs(); pollJob?.cancel()
        uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
    }

    private fun reattach() {
        val id   = prefs.getLong(KEY_ID, -1L)
        val name = prefs.getString(KEY_NAME, "") ?: ""
        if (id < 0 || name.isBlank()) return

        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: run { clearPrefs(); return }
        if (!c.moveToFirst()) { c.close(); clearPrefs(); return }
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        c.close()

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> scope.launch { handleComplete(id) }
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                uiState.update { it.copy(isDownloading = true, downloadingModel = name) }
                startPolling(id)
            }
            else -> clearPrefs()
        }
    }

    private fun startPolling(id: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && uiState.value.isDownloading) { poll(id); delay(1000) }
        }
    }

    private fun poll(id: Long) {
        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
        if (!c.moveToFirst()) { c.close(); return }
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val done   = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total  = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        c.close()

        when (status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> uiState.update {
                it.copy(
                    downloadProgress = if (total > 0) done.toFloat() / total else 0f,
                    downloadedMB = (done  / 1024 / 1024).toInt(),
                    totalMB = if (total > 0) (total / 1024 / 1024).toInt() else it.totalMB
                )
            }
            DownloadManager.STATUS_SUCCESSFUL -> scope.launch { handleComplete(id) }
            DownloadManager.STATUS_FAILED -> {
                pollJob?.cancel(); clearPrefs()
                uiState.update { it.copy(isDownloading = false,
                    error = "❌ Download fail hua. WiFi se try karo.") }
            }
        }
    }

    private suspend fun handleComplete(id: Long) = withContext(Dispatchers.IO) {
        pollJob?.cancel()
        val fileName = prefs.getString(KEY_FILENAME, null)
        clearPrefs()

        if (fileName == null) {
            uiState.update { it.copy(isDownloading = false, error = "fileName missing — dobara try karo") }
            return@withContext
        }

        // Source: where DownloadManager saved it
        val srcFile = File(externalDir(), fileName)
        // Destination: internal storage where JNI can always read
        val dstFile = File(internalDir(), fileName)

        Log.i(TAG, "Download complete. src=${srcFile.path} exists=${srcFile.exists()} size=${srcFile.length()}")

        if (!srcFile.exists()) {
            // Maybe it already moved to internal in a previous attempt
            if (dstFile.exists() && dstFile.length() > 50_000_000L) {
                Log.i(TAG, "File already in internal, loading from there")
                uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
                onComplete(dstFile.absolutePath)
                return@withContext
            }
            uiState.update { it.copy(isDownloading = false,
                error = "❌ Downloaded file nahi mila.\nExpected: ${srcFile.path}\n\nDobara download karo.") }
            return@withContext
        }

        val sizeMB = srcFile.length() / 1024 / 1024
        if (sizeMB < 10) {
            srcFile.delete()
            uiState.update { it.copy(isDownloading = false,
                error = "❌ File too small (${sizeMB}MB) — HuggingFace ne HTML bheja.\nWiFi pe switch karo aur dobara try karo.") }
            return@withContext
        }

        // Move to internal storage
        uiState.update { it.copy(downloadingModel = "Moving to internal storage…") }
        val moved = try {
            // Try rename first (instant on same filesystem)
            val renamed = srcFile.renameTo(dstFile)
            if (renamed) {
                Log.i(TAG, "Renamed (instant move) to ${dstFile.path}")
                true
            } else {
                // Fallback: stream copy
                Log.i(TAG, "Rename failed, doing stream copy…")
                streamCopy(srcFile, dstFile)
                srcFile.delete()
                Log.i(TAG, "Stream copy done: ${dstFile.length()/1024/1024}MB")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Move failed: ${e.message}")
            // Last resort: try loading from external directly
            Log.w(TAG, "Trying to load from external path as fallback")
            uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
            onComplete(srcFile.absolutePath)
            return@withContext
        }

        if (moved && dstFile.exists() && dstFile.length() > 50_000_000L) {
            Log.i(TAG, "✅ Ready: ${dstFile.path} (${dstFile.length()/1024/1024}MB)")
            uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
            onComplete(dstFile.absolutePath)
        } else {
            uiState.update { it.copy(isDownloading = false,
                error = "❌ File move failed. Dobara download karo.") }
        }
    }

    private fun streamCopy(src: File, dst: File) {
        val buf = ByteArray(256 * 1024)
        val total = src.length()
        var copied = 0L
        FileInputStream(src).use { inp ->
            FileOutputStream(dst).use { out ->
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    copied += n
                    uiState.update { it.copy(
                        downloadProgress = if (total > 0) copied.toFloat() / total else 0f
                    )}
                }
            }
        }
    }

    private fun clearPrefs() {
        prefs.edit().remove(KEY_ID).remove(KEY_NAME).remove(KEY_FILENAME).apply()
    }

    fun destroy() {
        pollJob?.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

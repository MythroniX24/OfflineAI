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
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "ModelDownload"
private const val PREFS = "model_dl_prefs"
private const val KEY_ID    = "dl_id"
private const val KEY_NAME  = "dl_model_name"
private const val KEY_FNAME = "dl_file_name"   // save fileName to find file after download

class ModelDownloadHelper(
    private val context: Context,
    private val uiState: MutableStateFlow<ModelUiState>,
    private val onComplete: (filePath: String) -> Unit,
    private val scope: CoroutineScope
) {
    private val dm    = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var progressJob: Job? = null

    // ── BroadcastReceiver for download complete ───────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id   = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val saved = prefs.getLong(KEY_ID, -1L)
            if (id == saved && id != -1L) {
                Log.i(TAG, "Download complete broadcast id=$id")
                scope.launch { handleComplete(id) }
            }
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

    // ── Start download ────────────────────────────────────────────────────────
    fun startDownload(model: DownloadableModel) {
        if (uiState.value.isDownloading) return

        // Check if already copied to internal storage
        val internal = internalFile(model.fileName)
        if (internal.exists() && internal.length() > 50_000_000L) {
            Log.i(TAG, "Already in internal: ${internal.path} (${internal.length()/1024/1024}MB)")
            scope.launch { onComplete(internal.absolutePath) }
            return
        }

        uiState.update {
            it.copy(isDownloading = true, downloadProgress = 0f,
                downloadingModel = model.name, totalMB = model.sizeMB,
                downloadedMB = 0, error = null)
        }

        // HuggingFace needs ?download=true for direct binary download
        val url = model.url.let {
            if (it.contains("huggingface.co") && !it.contains("?download=true"))
                "$it?download=true" else it
        }

        // Download to external Downloads (DownloadManager writes here well)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(model.name)
            .setDescription("OfflineAI model")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val id = dm.enqueue(request)
        Log.i(TAG, "Enqueued id=$id url=$url")

        prefs.edit()
            .putLong(KEY_ID, id)
            .putString(KEY_NAME, model.name)
            .putString(KEY_FNAME, model.fileName)
            .apply()

        startPolling(id)
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id >= 0) dm.remove(id)
        clearPrefs(); progressJob?.cancel()
        uiState.update { it.copy(isDownloading = false, downloadProgress = 0f) }
    }

    // ── Reattach to in-progress download after app restart ───────────────────
    private fun reattach() {
        val id    = prefs.getLong(KEY_ID, -1L)
        val name  = prefs.getString(KEY_NAME, "") ?: ""
        if (id < 0 || name.isBlank()) return

        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: run {
            clearPrefs(); return
        }
        if (!cursor.moveToFirst()) { cursor.close(); clearPrefs(); return }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        cursor.close()

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> scope.launch { handleComplete(id) }
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                uiState.update {
                    it.copy(isDownloading = true, downloadingModel = name, downloadProgress = 0f)
                }
                startPolling(id)
            }
            else -> clearPrefs()
        }
    }

    // ── Poll download progress ────────────────────────────────────────────────
    private fun startPolling(id: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && uiState.value.isDownloading) {
                poll(id); delay(1000)
            }
        }
    }

    private fun poll(id: Long) {
        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return
        if (!c.moveToFirst()) { c.close(); return }
        val status  = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val done    = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total   = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        c.close()

        when (status) {
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_PAUSED -> {
                val pct = if (total > 0) done.toFloat() / total else 0f
                uiState.update {
                    it.copy(downloadProgress = pct,
                        downloadedMB = (done  / 1024 / 1024).toInt(),
                        totalMB      = if (total > 0) (total / 1024 / 1024).toInt() else it.totalMB)
                }
            }
            DownloadManager.STATUS_SUCCESSFUL -> scope.launch { handleComplete(id) }
            DownloadManager.STATUS_FAILED -> {
                progressJob?.cancel(); clearPrefs()
                uiState.update { it.copy(isDownloading = false,
                    error = "Download failed. Internet check karo.") }
            }
        }
    }

    // ── Handle completed download ─────────────────────────────────────────────
    private suspend fun handleComplete(id: Long) {
        progressJob?.cancel()

        val fileName = prefs.getString(KEY_FNAME, null)
        clearPrefs()

        if (fileName == null) {
            uiState.update { it.copy(isDownloading = false,
                error = "File name missing — dobara download karo") }
            return
        }

        // ── Find the downloaded file ──────────────────────────────────────────
        val externalFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        Log.i(TAG, "Looking for: ${externalFile.path} exists=${externalFile.exists()} size=${externalFile.length()}")

        if (!externalFile.exists()) {
            uiState.update { it.copy(isDownloading = false,
                error = "Downloaded file nahi mila: ${externalFile.path}") }
            return
        }

        if (externalFile.length() < 10_000_000L) {        // < 10 MB = garbage/HTML
            val sizeMB = externalFile.length() / 1024 / 1024
            externalFile.delete()
            uiState.update { it.copy(isDownloading = false,
                error = "Download galat hua (${sizeMB}MB). HuggingFace ne HTML bheja. WiFi se dobara try karo.") }
            return
        }

        // ── CRITICAL: Copy to INTERNAL storage so JNI can always read it ─────
        // External storage path is sometimes inaccessible from native NDK code
        val internalDest = internalFile(fileName)

        uiState.update { it.copy(
            isDownloading = true,
            downloadingModel = "Copying to internal storage…",
            downloadProgress = 0f
        )}

        try {
            copyWithProgress(externalFile, internalDest)
            externalFile.delete()   // free external space after copy
            Log.i(TAG, "Copied to internal: ${internalDest.path} (${internalDest.length()/1024/1024}MB)")
        } catch (e: Exception) {
            // If copy fails, try loading from external directly as fallback
            Log.w(TAG, "Copy failed, trying external path: ${e.message}")
            uiState.update { it.copy(isDownloading = false) }
            onComplete(externalFile.absolutePath)
            return
        }

        uiState.update { it.copy(isDownloading = false, downloadProgress = 1f) }
        onComplete(internalDest.absolutePath)
    }

    // ── Copy file with progress updates ──────────────────────────────────────
    private suspend fun copyWithProgress(src: File, dst: File) = withContext(Dispatchers.IO) {
        val total = src.length()
        var copied = 0L
        val buf = ByteArray(128 * 1024)   // 128KB chunks
        FileInputStream(src).use { inp ->
            FileOutputStream(dst).use { out ->
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    copied += n
                    val pct = if (total > 0) copied.toFloat() / total else 0f
                    uiState.update { it.copy(
                        downloadProgress = pct,
                        downloadedMB = (copied / 1024 / 1024).toInt(),
                        totalMB = (total / 1024 / 1024).toInt()
                    )}
                }
            }
        }
    }

    private fun internalFile(fileName: String): File {
        val dir = File(context.filesDir, "models").also { it.mkdirs() }
        return File(dir, fileName)
    }

    private fun clearPrefs() {
        prefs.edit().remove(KEY_ID).remove(KEY_NAME).remove(KEY_FNAME).apply()
    }

    fun destroy() {
        progressJob?.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}

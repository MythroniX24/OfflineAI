package com.om.offlineai.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.om.offlineai.util.DeviceCapability
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface TokenCallback { fun onToken(token: String) }

sealed class ModelState {
    object Unloaded : ModelState()
    object Loading  : ModelState()
    data class Loaded(val name: String, val path: String) : ModelState()
    data class Error(val message: String) : ModelState()
}

@Singleton
class LlamaEngine @Inject constructor(
    private val context: Context,
    private val deviceCapability: DeviceCapability
) {
    companion object {
        private const val TAG = "LlamaEngine"
        init {
            try {
                System.loadLibrary("llama_android")
                Log.i(TAG, "✅ Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Native library FAILED: ${e.message}")
            }
        }
    }

    // Native methods — nativeLoadModel now takes FD number, not path string
    private external fun nativeInit()
    private external fun nativeLoadModelFd(fd: Int, nThreads: Int, nCtx: Int): Int
    private external fun nativeInfer(prompt: String, maxTokens: Int, callback: TokenCallback): String
    private external fun nativeStop()
    private external fun nativeFree()
    private external fun nativeGetModelInfo(): String

    var modelState: ModelState = ModelState.Unloaded
        private set

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferJob: Job? = null
    private var openPfd: ParcelFileDescriptor? = null

    fun init() {
        try { nativeInit() } catch (e: Exception) {
            Log.e(TAG, "nativeInit failed: ${e.message}")
        }
    }

    /**
     * Load model using ParcelFileDescriptor approach.
     * Passes FD to native, which opens /proc/self/fd/{fd} — bypasses all storage permission issues.
     * This is the same approach used by production apps like kotlinllamacpp.
     */
    suspend fun loadModel(path: String): Boolean = withContext(Dispatchers.IO) {
        modelState = ModelState.Loading

        val file = File(path)
        Log.i(TAG, "loadModel: path=$path exists=${file.exists()} size=${file.length()}")

        if (!file.exists()) {
            modelState = ModelState.Error("File not found: $path")
            return@withContext false
        }

        val sizeMB = file.length() / 1024 / 1024
        if (sizeMB < 10) {
            modelState = ModelState.Error(
                "File too small (${sizeMB}MB) — corrupted download. Dobara download karo.")
            return@withContext false
        }

        // Release any previously opened FD
        openPfd?.close()
        openPfd = null

        return@withContext try {
            // Open file as ParcelFileDescriptor — works on all Android versions
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val fd = pfd.detachFd()  // detach so JNI owns the FD lifetime
            openPfd = null           // pfd is detached, JNI handles close

            Log.i(TAG, "Passing FD=$fd to native (size=${sizeMB}MB)")

            val threads = deviceCapability.optimalThreadCount()
            val ctx     = deviceCapability.optimalContextSize()
            val result  = nativeLoadModelFd(fd, threads, ctx)

            if (result == 0) {
                modelState = ModelState.Loaded(file.name, path)
                Log.i(TAG, "✅ Model loaded: ${file.name}")
                true
            } else {
                modelState = ModelState.Error(
                    "llama.cpp model load failed (code=$result). File mein issue hai ya RAM kam hai.")
                Log.e(TAG, "❌ nativeLoadModelFd returned $result")
                false
            }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "❌ loadModel exception: $msg")
            modelState = ModelState.Error(msg)
            false
        }
    }

    fun infer(prompt: String, maxTokens: Int = 512): Flow<String> = flow {
        if (modelState !is ModelState.Loaded) {
            emit("[Model not loaded — pehle model load karo]")
            return@flow
        }
        val channel = Channel<String>(capacity = 256)
        val callback = object : TokenCallback {
            override fun onToken(token: String) { channel.trySend(token) }
        }
        inferJob = engineScope.launch {
            try { nativeInfer(prompt, maxTokens, callback) }
            finally { channel.close() }
        }
        try { for (token in channel) emit(token) }
        finally { inferJob?.cancel() }
    }

    fun stopInference() {
        try { nativeStop() } catch (e: Exception) { Log.e(TAG, "nativeStop: ${e.message}") }
        inferJob?.cancel()
    }

    fun getModelInfo(): String = try { nativeGetModelInfo() } catch (_: Exception) { "{}" }

    fun freeModel() {
        try {
            nativeFree()
            openPfd?.close()
            openPfd = null
            modelState = ModelState.Unloaded
        } catch (e: Exception) { Log.e(TAG, "freeModel: ${e.message}") }
    }

    fun isLoaded() = modelState is ModelState.Loaded
    fun destroy()  { engineScope.cancel(); freeModel() }
}

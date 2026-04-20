package com.om.offlineai.engine

import android.content.Context
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
                Log.i(TAG, "Native library loaded OK")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library load FAILED: ${e.message}")
            }
        }
    }

    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, nThreads: Int, nCtx: Int): Int
    private external fun nativeInfer(prompt: String, maxTokens: Int, callback: TokenCallback): String
    private external fun nativeStop()
    private external fun nativeFree()
    private external fun nativeGetModelInfo(): String

    var modelState: ModelState = ModelState.Unloaded
        private set

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferJob: Job? = null

    fun init() {
        try { nativeInit() } catch (e: Exception) {
            Log.e(TAG, "nativeInit failed: ${e.message}")
        }
    }

    suspend fun loadModel(path: String): Boolean = withContext(Dispatchers.IO) {
        modelState = ModelState.Loading
        val file = File(path)

        // ── Pre-checks before calling JNI ────────────────────────────────────
        if (!file.exists()) {
            val msg = "File not found: $path"
            Log.e(TAG, msg)
            modelState = ModelState.Error(msg)
            return@withContext false
        }
        if (!file.canRead()) {
            val msg = "Cannot read file (permission denied): $path"
            Log.e(TAG, msg)
            modelState = ModelState.Error(msg)
            return@withContext false
        }
        val sizeMB = file.length() / 1024 / 1024
        if (sizeMB < 10) {
            val msg = "File too small (${sizeMB}MB) — probably not a valid GGUF model"
            Log.e(TAG, msg)
            modelState = ModelState.Error(msg)
            return@withContext false
        }
        if (!deviceCapability.canLoadModel(sizeMB)) {
            val available = deviceCapability.profile().ramMB
            val msg = "Not enough RAM — model is ${sizeMB}MB, only ${available}MB available"
            Log.e(TAG, msg)
            modelState = ModelState.Error(msg)
            return@withContext false
        }

        Log.i(TAG, "Loading: $path | size=${sizeMB}MB | readable=${file.canRead()}")

        try {
            val threads = deviceCapability.optimalThreadCount()
            val ctx     = deviceCapability.optimalContextSize()
            val result  = nativeLoadModel(path, threads, ctx)
            if (result == 0) {
                modelState = ModelState.Loaded(file.name, path)
                Log.i(TAG, "Model loaded successfully: ${file.name}")
                true
            } else {
                val msg = "llama.cpp failed to load model (code=$result). File may be corrupted."
                Log.e(TAG, msg)
                modelState = ModelState.Error(msg)
                false
            }
        } catch (e: Exception) {
            val msg = "Exception loading model: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, msg)
            modelState = ModelState.Error(msg)
            false
        }
    }

    fun infer(prompt: String, maxTokens: Int = 512): Flow<String> = flow {
        if (modelState !is ModelState.Loaded) {
            emit("[Model not loaded]"); return@flow
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
            modelState = ModelState.Unloaded
        } catch (e: Exception) { Log.e(TAG, "nativeFree: ${e.message}") }
    }

    fun isLoaded() = modelState is ModelState.Loaded

    fun destroy() { engineScope.cancel(); freeModel() }
}

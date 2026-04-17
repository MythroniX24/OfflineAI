package com.om.offlineai.engine

import android.content.Context
import android.util.Log
import com.om.offlineai.util.DeviceCapability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Callback interface passed into native layer for streaming tokens */
interface TokenCallback {
    fun onToken(token: String)
}

sealed class ModelState {
    object Unloaded : ModelState()
    object Loading : ModelState()
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
            // Load the native library compiled via CMake
            try {
                System.loadLibrary("llama_android")
                Log.i(TAG, "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    // ── JNI declarations ────────────────────────────────────────────────────
    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, nThreads: Int, nCtx: Int): Int
    private external fun nativeInfer(prompt: String, maxTokens: Int, callback: TokenCallback): String
    private external fun nativeStop()
    private external fun nativeFree()
    private external fun nativeGetModelInfo(): String

    // ── State ────────────────────────────────────────────────────────────────
    var modelState: ModelState = ModelState.Unloaded
        private set

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun init() {
        try { nativeInit() } catch (e: Exception) { Log.e(TAG, "nativeInit failed: ${e.message}") }
    }

    /** Load model from file path. Returns success. */
    suspend fun loadModel(path: String): Boolean = withContext(Dispatchers.IO) {
        modelState = ModelState.Loading
        try {
            val threads = deviceCapability.optimalThreadCount()
            val ctx = deviceCapability.optimalContextSize()
            Log.i(TAG, "Loading model threads=$threads ctx=$ctx")
            val result = nativeLoadModel(path, threads, ctx)
            if (result == 0) {
                val name = java.io.File(path).name
                modelState = ModelState.Loaded(name, path)
                Log.i(TAG, "Model loaded: $name")
                true
            } else {
                modelState = ModelState.Error("Failed to load model (code=$result)")
                false
            }
        } catch (e: Exception) {
            modelState = ModelState.Error(e.message ?: "Unknown error")
            Log.e(TAG, "loadModel exception: ${e.message}")
            false
        }
    }

    /** Run inference with streaming tokens. Returns Flow<String> of token pieces. */
    fun infer(
        prompt: String,
        maxTokens: Int = 512
    ): Flow<String> = flow {
        if (modelState !is ModelState.Loaded) {
            emit("[ERROR: Model not loaded]")
            return@flow
        }
        // Channel to bridge JNI callback → coroutine flow
        val channel = kotlinx.coroutines.channels.Channel<String>(capacity = 256)

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                // Called from native thread — non-blocking offer
                channel.trySend(token)
            }
        }

        // Run inference in a separate thread (never block UI)
        inferJob = engineScope.launch {
            try {
                nativeInfer(prompt, maxTokens, callback)
            } finally {
                channel.close()
            }
        }

        // Collect and emit tokens
        try {
            for (token in channel) {
                emit(token)
            }
        } finally {
            inferJob?.cancel()
        }
    }

    /** Stop current inference immediately */
    fun stopInference() {
        try { nativeStop() } catch (e: Exception) { Log.e(TAG, "nativeStop: ${e.message}") }
        inferJob?.cancel()
    }

    /** Get model metadata JSON */
    fun getModelInfo(): String {
        return try { nativeGetModelInfo() } catch (e: Exception) { "{}" }
    }

    /** Release model from memory */
    fun freeModel() {
        try {
            nativeFree()
            modelState = ModelState.Unloaded
        } catch (e: Exception) {
            Log.e(TAG, "nativeFree: ${e.message}")
        }
    }

    fun isLoaded() = modelState is ModelState.Loaded

    fun destroy() {
        engineScope.cancel()
        freeModel()
    }
}

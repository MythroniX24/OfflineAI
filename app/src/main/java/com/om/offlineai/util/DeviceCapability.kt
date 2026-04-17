package com.om.offlineai.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device capabilities and returns tuned defaults for inference.
 * Prevents UI freezes and thermal throttling on low-end devices.
 */
@Singleton
class DeviceCapability @Inject constructor(private val context: Context) {

    data class Profile(
        val tier: Tier,
        val threads: Int,
        val contextSize: Int,
        val maxTokens: Int,
        val ramMB: Long
    )

    enum class Tier { LOW, MID, HIGH }

    fun profile(): Profile {
        val cores = Runtime.getRuntime().availableProcessors()
        val ramMB = availableRamMB()
        val tier = when {
            cores >= 8 && ramMB >= 6000 -> Tier.HIGH
            cores >= 4 && ramMB >= 3000 -> Tier.MID
            else                         -> Tier.LOW
        }
        return Profile(
            tier       = tier,
            threads    = optimalThreadCount(cores),
            contextSize = optimalContextSize(ramMB),
            maxTokens  = when (tier) { Tier.HIGH -> 1024; Tier.MID -> 512; else -> 256 },
            ramMB      = ramMB
        )
    }

    /** Use half the physical cores (leave headroom for UI + system) */
    fun optimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return optimalThreadCount(cores)
    }

    private fun optimalThreadCount(cores: Int) = (cores / 2).coerceIn(1, 6)

    /** Context window inversely scales with RAM pressure */
    fun optimalContextSize(): Int = optimalContextSize(availableRamMB())

    private fun optimalContextSize(ramMB: Long) = when {
        ramMB >= 6000 -> 4096
        ramMB >= 3000 -> 2048
        else           -> 1024
    }

    /** Check if the device has enough RAM to likely load a model of given sizeMB */
    fun canLoadModel(modelSizeMB: Long): Boolean {
        val ram = availableRamMB()
        // Require at least 1.5x model size as free RAM
        return ram >= (modelSizeMB * 1.5)
    }

    private fun availableRamMB(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    fun totalRamMB(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun isLowMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    fun deviceSummary(): String {
        val p = profile()
        return "Device: ${Build.MODEL} | " +
               "Cores: ${Runtime.getRuntime().availableProcessors()} | " +
               "RAM: ${p.ramMB}MB | Tier: ${p.tier}"
    }
}

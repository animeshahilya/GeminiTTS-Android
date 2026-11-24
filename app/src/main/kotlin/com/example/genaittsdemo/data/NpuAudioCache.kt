package com.example.genaittsdemo.data

import android.content.Context
import android.speech.tts.SynthesisCallback
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class CacheStats(
    val memoryHits: Int,
    val diskHits: Int,
    val misses: Int,
    val totalEntries: Int,
    val diskSizeBytes: Long
) {
    val totalHits: Int get() = memoryHits + diskHits
    val hitRate: Float get() {
        val total = totalHits + misses
        return if (total == 0) 0f else (totalHits.toFloat() / total) * 100f
    }
}

/**
 * Ultra-fast two-tiered (Memory LRU + Persistent Disk) PCM audio cache.
 * Designed to drastically cut latency for frequently repeated TalkBack UI phrases
 * (e.g., "Double-tap to activate", button labels, punctuation, numbers)
 * while offloading repetitive inference workloads from the device NPU.
 */
class NpuAudioCache(private val context: Context) {

    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NpuAudioCache"
        private const val MAX_MEMORY_CACHE_BYTES = 16 * 1024 * 1024 // 16 MB memory cache
        private const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L // 64 MB disk cache
        private const val CACHE_DIR_NAME = "npu_tts_pcm_cache"

        @Volatile
        private var instance: NpuAudioCache? = null

        fun getInstance(context: Context): NpuAudioCache {
            return instance ?: synchronized(this) {
                instance ?: NpuAudioCache(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    // Memory LRU Cache: keys are cache keys, values are ByteArray of PCM data
    private val memoryCache = object : LruCache<String, ByteArray>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int {
            return value.size
        }
    }

    private val memHitCount = AtomicInteger(0)
    private val diskHitCount = AtomicInteger(0)
    private val missCount = AtomicInteger(0)
    private val totalSynthesizedBytes = AtomicLong(0)

    var isCacheEnabled: Boolean = true

    /**
     * Compute a deterministic cache key for text, voice, rate, and pitch.
     */
    fun createCacheKey(
        text: String,
        voiceName: String,
        speechRate: Int = 100,
        pitch: Int = 100
    ): String {
        val normalized = text.trim().lowercase()
        val rawKey = "$voiceName|$speechRate|$pitch|$normalized"
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(rawKey.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            rawKey.hashCode().toString()
        }
    }

    /**
     * Retrieves cached audio data for a given cache key, checking memory first then disk.
     * Returns null if not cached or cache is disabled.
     */
    fun get(cacheKey: String): ByteArray? {
        if (!isCacheEnabled) return null

        // 1. Check Memory Cache (0-1ms)
        val memAudio = memoryCache.get(cacheKey)
        if (memAudio != null) {
            memHitCount.incrementAndGet()
            Log.d(TAG, "Cache HIT (Memory) for key: ${cacheKey.take(8)} (${memAudio.size} bytes)")
            return memAudio
        }

        // 2. Check Disk Cache (2-5ms)
        val file = File(cacheDir, "$cacheKey.pcm")
        if (file.exists() && file.isFile) {
            return try {
                val diskAudio = file.readBytes()
                if (diskAudio.isNotEmpty()) {
                    // Populate memory cache for subsequent fast hits
                    memoryCache.put(cacheKey, diskAudio)
                    diskHitCount.incrementAndGet()
                    Log.d(TAG, "Cache HIT (Disk) for key: ${cacheKey.take(8)} (${diskAudio.size} bytes)")
                    diskAudio
                } else {
                    file.delete()
                    missCount.incrementAndGet()
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read disk cache for $cacheKey", e)
                missCount.incrementAndGet()
                null
            }
        }

        missCount.incrementAndGet()
        return null
    }

    /**
     * Store PCM audio into both memory LRU and persistent disk storage.
     */
    fun put(cacheKey: String, audioData: ByteArray) {
        if (!isCacheEnabled || audioData.isEmpty()) return

        // Put in memory (hot path stays synchronous)
        memoryCache.put(cacheKey, audioData)
        totalSynthesizedBytes.addAndGet(audioData.size.toLong())

        // Save to disk on an IO dispatcher so the TTS binder thread never blocks
        ioScope.launch {
            try {
                val file = File(cacheDir, "$cacheKey.pcm")
                FileOutputStream(file).use { fos ->
                    fos.write(audioData)
                }
                trimDiskCacheIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write disk cache for $cacheKey", e)
            }
        }
    }

    /**
     * Helper to stream cached audio directly to a system SynthesisCallback.
     * Writes in chunks up to callback.maxBufferSize.
     */
    fun streamToCallback(audioData: ByteArray, callback: SynthesisCallback): Boolean {
        try {
            val maxBuffer = callback.maxBufferSize
            var offset = 0
            while (offset < audioData.size) {
                val length = minOf(maxBuffer, audioData.size - offset)
                val ret = callback.audioAvailable(audioData, offset, length)
                if (ret != 0) {
                    Log.w(TAG, "callback.audioAvailable returned $ret (interrupted)")
                    return false
                }
                offset += length
            }
            callback.done()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming cached audio to callback", e)
            callback.error()
            return false
        }
    }

    /**
     * Evicts oldest disk files if directory exceeds MAX_DISK_CACHE_BYTES.
     */
    private fun trimDiskCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > MAX_DISK_CACHE_BYTES) {
                // Sort by last modified time ascending (oldest first)
                val sorted = files.sortedBy { it.lastModified() }
                for (f in sorted) {
                    if (totalSize <= MAX_DISK_CACHE_BYTES * 0.75) break
                    val len = f.length()
                    if (f.delete()) {
                        totalSize -= len
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error trimming disk cache", e)
        }
    }

    fun clear() {
        ioScope.cancel()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        memoryCache.evictAll()
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing disk cache", e)
        }
        memHitCount.set(0)
        diskHitCount.set(0)
        missCount.set(0)
        Log.i(TAG, "Cache cleared successfully")
    }

    /**
     * Purges in-memory LRU cache when system reports memory pressure (onTrimMemory / onLowMemory),
     * while keeping persisted disk cache intact.
     */
    fun trimMemory() {
        memoryCache.evictAll()
        Log.d(TAG, "Purged in-memory audio cache due to OS memory pressure")
    }

    fun getStats(): CacheStats {
        val files = cacheDir.listFiles() ?: emptyArray()
        val diskSize = files.sumOf { it.length() }
        return CacheStats(
            memoryHits = memHitCount.get(),
            diskHits = diskHitCount.get(),
            misses = missCount.get(),
            totalEntries = memoryCache.size() + files.size,
            diskSizeBytes = diskSize
        )
    }

    /**
     * AudioAccumulator collects chunks during live NPU synthesis so they can be cached once DONE.
     */
    class AudioAccumulator {
        private val outputStream = ByteArrayOutputStream(32 * 1024)

        @Synchronized
        fun append(chunk: ByteArray) {
            outputStream.write(chunk)
        }

        @Synchronized
        fun toByteArray(): ByteArray {
            return outputStream.toByteArray()
        }

        @get:Synchronized
        val size: Int get() = outputStream.size()

        @Synchronized
        fun reset() {
            outputStream.reset()
        }
    }
}

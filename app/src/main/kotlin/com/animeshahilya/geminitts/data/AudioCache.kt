package com.animeshahilya.geminitts.data

import android.content.Context
import android.util.Log
import android.util.LruCache
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Remembers synthesized audio for repeated phrases (button labels, "Double-tap to activate"), in
 * memory and on disk. Audio is stored at 1x, so it replays at any speech rate.
 */
class AudioCache private constructor(context: Context) {

    class Clip(val sampleRate: Int, val channels: Int, val pcm: ByteArray)

    private val dir = File(context.createDeviceProtectedStorageContext().cacheDir, "pcm").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()

    private val memory = object : LruCache<String, Clip>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Clip) = value.pcm.size
    }

    /** Case matters: "US" and "us" are said differently. */
    fun key(text: String, voiceName: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$voiceName\u0000$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun get(key: String): Clip? {
        memory.get(key)?.let { return it }
        val file = File(dir, key)
        if (!file.isFile) return null
        return try {
            val clip = DataInputStream(file.inputStream().buffered()).use { input ->
                val rate = input.readInt()
                val channels = input.readInt()
                Clip(rate, channels, input.readBytes())
            }
            file.setLastModified(System.currentTimeMillis()) // keeps eviction least-recently-used
            memory.put(key, clip)
            clip
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unreadable cache entry", e)
            file.delete()
            null
        }
    }

    fun put(key: String, clip: Clip) {
        if (clip.pcm.isEmpty() || clip.pcm.size > MAX_ENTRY_BYTES) return
        memory.put(key, clip)
        io.execute {
            try {
                val tmp = File(dir, "$key.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(clip.sampleRate)
                    out.writeInt(clip.channels)
                    out.write(clip.pcm)
                }
                tmp.renameTo(File(dir, key))
                trimDisk()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save cache entry", e)
            }
        }
    }

    private fun trimDisk() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    fun clear() {
        memory.evictAll()
        io.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    fun trimMemory() = memory.evictAll()

    /** Number of saved phrases and their size on disk. */
    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    companion object {
        private const val TAG = "AudioCache"
        private const val MAX_MEMORY_BYTES = 8 * 1024 * 1024
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
        /** About 15 s of 24 kHz mono: long passages would push out hundreds of short UI phrases. */
        const val MAX_ENTRY_BYTES = 24_000 * 2 * 15

        @Volatile private var instance: AudioCache? = null

        fun get(context: Context): AudioCache = instance ?: synchronized(this) {
            instance ?: AudioCache(context.applicationContext).also { instance = it }
        }
    }
}

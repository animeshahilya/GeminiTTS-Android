package com.example.genaittsdemo.service

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File

/**
 * PerformanceScope manages system acceleration and process guarantees during speech synthesis:
 *
 * 1. ADPF (Android Dynamic Performance Framework) via [PerformanceHintManager] (API 31+):
 *    Informs the Energy-Aware Scheduler (EAS) CPU governor of workload latency targets (default 50ms),
 *    triggering fast frequency scaling onto Cortex-X4 prime and A720 performance cores without waiting
 *    for reactive CPU load scaling.
 *
 * 2. Native Thread Priority Management (ProcTasks / Linux Task Discovery):
 *    Inspects `/proc/self/task` to discover all active Java and native C++ worker threads (including
 *    ML Kit on-device NPU/TPU inference threads) and elevates their scheduling priority to
 *    [Process.THREAD_PRIORITY_URGENT_AUDIO].
 *
 * 3. Screen-Off Audio Delivery ([PowerManager.PARTIAL_WAKE_LOCK]):
 *    Prevents Android's power manager from throttling or suspending background synthesis threads
 *    when the device screen turns off or locks (essential for screen reader accessibility).
 */
class PerformanceScope(
    private val context: Context,
    private val targetDurationNanos: Long = 50_000_000L // 50ms target chunk duration
) : AutoCloseable {

    companion object {
        private const val TAG = "PerformanceScope"
        private const val WAKE_LOCK_TAG = "GenAiTTS:Synthesis"
        private const val WAKE_LOCK_TIMEOUT_MS = 15_000L // 15s safety limit to prevent battery drain

        /**
         * Discovers all active thread IDs belonging to this process via `/proc/self/task`.
         * Captures both managed Android runtime threads and native C++/NPU runtime worker pools.
         */
        fun getProcessThreadIds(): IntArray {
            return try {
                val taskDir = File("/proc/self/task")
                val files = taskDir.list()
                if (files != null && files.isNotEmpty()) {
                    val tids = files.mapNotNull { it.toIntOrNull() }.filter { it > 0 }
                    if (tids.isNotEmpty()) tids.toIntArray() else intArrayOf(Process.myTid())
                } else {
                    intArrayOf(Process.myTid())
                }
            } catch (_: Exception) {
                intArrayOf(Process.myTid())
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var hintSession: PerformanceHintManager.Session? = null
    private val managedThreadPriorities = mutableMapOf<Int, Int>()
    private var workStartNanos: Long = 0L

    init {
        acquire()
    }

    private fun acquire() {
        // 1. Thread priority elevation for calling thread & native worker threads
        val allTids = getProcessThreadIds()
        try {
            for (tid in allTids) {
                try {
                    val oldPriority = Process.getThreadPriority(tid)
                    managedThreadPriorities[tid] = oldPriority
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Exception) {
                    // Ignore threads we cannot prioritize or that exited
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed during thread priority elevation: ${e.message}")
        }

        // 2. Partial WakeLock with safety timeout
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        // 3. ADPF PerformanceHintManager initialization (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val hintManager = context.getSystemService(PerformanceHintManager::class.java)
                hintSession = hintManager?.createHintSession(allTids, targetDurationNanos)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize ADPF PerformanceHintSession: ${e.message}")
            }
        }
    }

    /**
     * Marks the beginning of a synthesis work burst.
     */
    fun beginWork() {
        workStartNanos = System.nanoTime()
    }

    /**
     * Reports actual elapsed synthesis work duration since [beginWork] to the ADPF EAS CPU governor.
     */
    fun report() {
        if (workStartNanos > 0L) {
            val elapsed = System.nanoTime() - workStartNanos
            report(elapsed)
            workStartNanos = 0L
        }
    }

    /**
     * Reports explicit elapsed synthesis work duration in nanoseconds to the ADPF governor.
     * This allows EAS to dynamically trim or elevate core frequencies to match real work pacing.
     * Clamps reported intervals to prevent PowerHAL libperfmgr outlier rejection warnings.
     */
    fun report(durationNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && durationNanos > 0) {
            try {
                // Clamping to [1ms, 3x targetDurationNanos] ensures Energy-Aware Scheduler (EAS)
                // receives valid burst signals without powerhal rejecting multi-second total times as outliers.
                val clampedDuration = durationNanos.coerceIn(1_000_000L, targetDurationNanos * 3)
                hintSession?.reportActualWorkDuration(clampedDuration)
            } catch (e: Exception) {
                Log.w(TAG, "Error reporting actual work duration to ADPF: ${e.message}")
            }
        }
    }

    /**
     * Updates active threads in ADPF session on Android 14+ (API 34) if new worker threads spawned.
     */
    fun refreshThreads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val tids = getProcessThreadIds()
                hintSession?.setThreads(tids)
            } catch (_: Exception) {}
        }
    }

    override fun close() {
        // 1. Close ADPF session
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                hintSession?.close()
                hintSession = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ADPF hint session: ${e.message}")
            }
        }

        // 2. Safely release WakeLock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }

        // 3. Restore thread priorities
        for ((tid, prio) in managedThreadPriorities) {
            try {
                Process.setThreadPriority(tid, prio)
            } catch (_: Exception) {}
        }
        managedThreadPriorities.clear()
    }
}

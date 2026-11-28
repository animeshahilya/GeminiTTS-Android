package com.animeshahilya.geminitts.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.view.accessibility.AccessibilityManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Identifies TTS callers from [android.speech.tts.SynthesisRequest.getCallerUid] and discovers
 * screen readers / user apps for per-app speech profiles without needing QUERY_ALL_PACKAGES.
 */
object CallerTracker {

    private val uidCache = ConcurrentHashMap<Int, String>()

    /** Caller's primary package name, or null for this app itself or unknown UIDs. */
    fun callerPackage(context: Context, uid: Int): String? {
        if (uid < 0 || uid == Process.myUid()) return null
        return uidCache.getOrPut(uid) {
            val pkgs = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull()
            pickPackage(pkgs)
        }.ifEmpty { null }
    }

    internal fun pickPackage(packages: Array<String>?): String {
        if (packages.isNullOrEmpty()) return ""
        return if ("android" in packages) "android" else packages.min()
    }

    /** Package names of currently enabled spoken-feedback accessibility services (e.g. TalkBack). */
    fun spokenAccessibilityPackages(context: Context): Set<String> {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return emptySet()
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .mapNotNullTo(HashSet()) { it.resolveInfo?.serviceInfo?.packageName }
    }

    /** True when [packageName] belongs to an enabled spoken-feedback accessibility service (e.g. TalkBack). */
    fun isSpokenAccessibilityService(context: Context, packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName in spokenAccessibilityPackages(context)

    /** Human-readable app name for [packageName], falling back to the package name itself. */
    fun appLabel(context: Context, packageName: String): String {
        if (packageName == "android") return "Android System"
        val pm = context.packageManager
        return runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    /** Lists visible screen readers and launcher apps (sorted by label) for choosing per-app overrides. */
    fun discoverableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, String>()

        fun addFromIntent(intent: Intent) {
            val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in seen) continue
                seen[pkg] = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            }
        }

        val a11yServices = runCatching {
            pm.queryIntentServices(Intent(AccessibilityService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        for (ri in a11yServices) {
            val pkg = ri.serviceInfo?.packageName ?: continue
            if (pkg == context.packageName || pkg in seen) continue
            seen[pkg] = appLabel(context, pkg)
        }

        addFromIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        return seen.entries
            .map { it.key to it.value }
            .sortedBy { it.second.lowercase() }
    }
}

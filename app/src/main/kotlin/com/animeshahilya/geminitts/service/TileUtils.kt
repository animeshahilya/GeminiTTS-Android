package com.animeshahilya.geminitts.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Launches [intent] from a Quick Settings tile using the non-deprecated API 34+ PendingIntent path. */
internal fun TileService.launchActivityAndCollapse(intent: Intent, requestCode: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pi = PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pi)
    } else {
        @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}

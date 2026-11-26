package com.animeshahilya.geminitts.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.animeshahilya.geminitts.MainActivity
import com.animeshahilya.geminitts.data.TtsPrefs

/** Quick Settings tile that cycles through saved Voice Presets in one tap. */
class VoicePresetTile : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val prefs = TtsPrefs(this)
        val next = prefs.cycleNextPreset()
        if (next == null) {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val prefs = TtsPrefs(this)
        val current = prefs.currentPresetName()
        val hasPresets = prefs.presets.isNotEmpty()
        tile.state = if (current != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                current != null -> current
                hasPresets -> "Tap to apply"
                else -> "Save preset in app"
            }
        }
        tile.updateTile()
    }
}

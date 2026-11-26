package com.animeshahilya.geminitts.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.animeshahilya.geminitts.data.RecentSpeech
import com.animeshahilya.geminitts.data.TtsPrefs
import com.animeshahilya.geminitts.ui.RecentSpeechActivity

/**
 * Quick Settings tile to review, replay slowly, spell, or copy the most recent speech.
 * Adding the tile turns recent-speech capture on; removing it clears the buffer immediately.
 */
class RecentSpeechTile : TileService() {

    override fun onTileAdded() {
        TtsPrefs(this).recentSpeechEnabled = true
        updateTile()
    }

    override fun onTileRemoved() {
        TtsPrefs(this).recentSpeechEnabled = false
        RecentSpeech.clear()
    }

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        TtsPrefs(this).recentSpeechEnabled = true
        val intent = Intent(this, RecentSpeechActivity::class.java)
            .putExtra(RecentSpeechActivity.EXTRA_OPENED_AT_MS, System.currentTimeMillis())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val count = RecentSpeech.items().size
            tile.subtitle = if (count > 0) "$count recent" else "Ready"
        }
        tile.updateTile()
    }
}

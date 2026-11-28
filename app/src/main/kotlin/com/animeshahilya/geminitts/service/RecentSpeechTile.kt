package com.animeshahilya.geminitts.service

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
        launchActivityAndCollapse(intent, requestCode = 0)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = TtsPrefs(this).recentSpeechEnabled
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (!enabled) {
                "Off"
            } else {
                val count = RecentSpeech.items().size
                if (count > 0) "$count recent" else "Ready"
            }
        }
        tile.updateTile()
    }
}

package dev.aioverlay

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings switch: one tap hides/shows the AI bubble. */
class ToggleTile : TileService() {
    override fun onStartListening() {
        val s = OverlayService.instance
        qsTile.state = when {
            s == null -> Tile.STATE_UNAVAILABLE // service off in Accessibility
            s.hidden -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        qsTile.updateTile()
    }

    override fun onClick() {
        OverlayService.instance?.toggleHidden()
        onStartListening()
    }
}

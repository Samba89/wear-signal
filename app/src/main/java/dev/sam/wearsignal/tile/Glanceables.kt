package dev.sam.wearsignal.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.sam.wearsignal.complication.UnreadComplicationService
import org.signal.core.util.logging.Log

/**
 * Pushes fresh data to the glanceable surfaces (tile + complication). Called after
 * every poll and whenever the unread baseline moves (the app being opened).
 * Best-effort: a rejected update request must never take down the caller.
 */
object Glanceables {

  private val TAG = Log.tag(Glanceables::class)

  fun requestUpdate(context: Context) {
    try {
      TileService.getUpdater(context).requestUpdate(RecentTileService::class.java)
    } catch (e: SecurityException) {
      Log.w(TAG, "Tile update request rejected", e)
    }
    ComplicationDataSourceUpdateRequester
      .create(context, ComponentName(context, UnreadComplicationService::class.java))
      .requestUpdateAll()
  }
}

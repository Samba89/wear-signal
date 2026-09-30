package dev.sam.wearsignal.tile

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.sam.wearsignal.complication.UnreadComplicationService

/**
 * Pushes fresh data to the glanceable surfaces (tile + complication). Called after
 * every poll and whenever the unread baseline moves (the app being opened).
 */
object Glanceables {

  fun requestUpdate(context: Context) {
    TileService.getUpdater(context).requestUpdate(RecentTileService::class.java)
    ComplicationDataSourceUpdateRequester
      .create(context, ComponentName(context, UnreadComplicationService::class.java))
      .requestUpdateAll()
  }
}

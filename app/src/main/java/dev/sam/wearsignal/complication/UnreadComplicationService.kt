package dev.sam.wearsignal.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.R
import dev.sam.wearsignal.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Watch-face complication showing the unread count (incoming messages since the app
 * was last open). Update period is 0: it never polls on its own — [dev.sam.wearsignal.tile.Glanceables]
 * pushes updates after every poll, so wearing it costs no battery.
 */
class UnreadComplicationService : SuspendingComplicationDataSourceService() {

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.SHORT_TEXT) shortText("2") else null

  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    if (request.complicationType != ComplicationType.SHORT_TEXT) return null
    val unread = withContext(Dispatchers.IO) {
      if (AppDeps.account.isLinked) AppDeps.messages.unreadCount() else 0
    }
    return shortText(unread.toString())
  }

  private fun shortText(value: String): ComplicationData {
    val tapAction = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return ShortTextComplicationData.Builder(
      PlainComplicationText.Builder(value).build(),
      PlainComplicationText.Builder("Unread Signal messages").build()
    )
      .setMonochromaticImage(
        MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_message)).build()
      )
      .setTapAction(tapAction)
      .build()
  }
}

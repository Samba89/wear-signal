package dev.sam.wearsignal.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.sam.wearsignal.messages.EnvelopeProcessor
import dev.sam.wearsignal.ui.MainActivity
import org.signal.core.util.logging.Log

/**
 * Pops message notifications on the watch (used only when the phone is disconnected).
 */
class NotificationPresenter(private val context: Context) {

  companion object {
    private val TAG = Log.tag(NotificationPresenter::class)
    private const val CHANNEL_ID = "messages"
  }

  init {
    val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
      enableVibration(true)
      description = "Signal messages received while away from phone"
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun notify(messages: List<EnvelopeProcessor.IncomingMessage>, nameResolver: (String) -> String) {
    if (messages.isEmpty()) return
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Notification permission not granted")
      return
    }

    val contentIntent = PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val manager = NotificationManagerCompat.from(context)

    messages.filterNot { it.fromSelf }.takeLast(5).forEachIndexed { index, message ->
      val sender = nameResolver(message.senderAci)
      val title = if (message.groupId != null) "$sender (group)" else sender
      val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(title)
        .setContentText(message.body)
        .setWhen(message.sentAt)
        .setContentIntent(contentIntent)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
      manager.notify((message.sentAt % Int.MAX_VALUE).toInt() + index, notification)
    }
  }
}

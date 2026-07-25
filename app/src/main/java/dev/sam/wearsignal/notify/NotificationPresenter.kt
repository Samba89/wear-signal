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
import androidx.core.app.RemoteInput
import dev.sam.wearsignal.calls.CallLog
import dev.sam.wearsignal.messages.EnvelopeProcessor
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.attachmentPlaceholder
import dev.sam.wearsignal.ui.MainActivity
import org.signal.core.util.logging.Log

/**
 * Pops message notifications on the watch (used only when the phone is disconnected).
 */
class NotificationPresenter(private val context: Context) {

  companion object {
    private val TAG = Log.tag(NotificationPresenter::class)
    private const val CHANNEL_ID = "messages"
    private const val CALLS_CHANNEL_ID = "calls"
    const val KEY_REPLY_TEXT = "reply_text"
    const val EXTRA_PEER = "peer"
    const val EXTRA_IS_GROUP = "is_group"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
  }

  init {
    val channel = NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
      enableVibration(true)
      description = "Signal messages received while away from phone"
    }
    val callsChannel = NotificationChannel(CALLS_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
      enableVibration(true)
      description = "Signal calls missed while away from phone"
    }
    context.getSystemService(NotificationManager::class.java).let {
      it.createNotificationChannel(channel)
      it.createNotificationChannel(callsChannel)
    }
  }

  /** Missed-call notifications, mirroring message semantics (only when the phone isn't covering us). */
  fun notifyMissedCalls(calls: List<CallLog.Entry>, nameResolver: (String) -> String) {
    if (calls.isEmpty()) return
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
    calls.takeLast(3).forEachIndexed { index, call ->
      val who = if (call.isGroup) GroupStateResolver.cachedTitle(call.peer) ?: "Group" else nameResolver(call.peer)
      val notification = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_missed_call)
        .setContentTitle(if (call.isVideo) "Missed video call" else "Missed call")
        .setContentText(who)
        .setWhen(call.startedAt)
        .setShowWhen(true)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
      // Distinct id space from message notifications (those use raw sentAt % MAX),
      // so a call and a message with the same timestamp can't replace each other.
      manager.notify("call:${call.startedAt}:$index".hashCode(), notification)
    }
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
      val title = message.groupId
        ?.let { groupId -> GroupStateResolver.cachedTitle(groupId)?.let { "$sender @ $it" } ?: "$sender (group)" }
        ?: sender
      val notificationId = (message.sentAt % Int.MAX_VALUE).toInt() + index
      val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(title)
        .setContentText(message.body.ifEmpty { attachmentPlaceholder(message.attachmentType) })
        .setWhen(message.sentAt)
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

      // Wear's native reply (voice/keyboard/canned) via RemoteInput; groups fan out on send.
      builder.addAction(buildReplyAction(message.peer, message.groupId != null, notificationId))

      manager.notify(notificationId, builder.build())
    }
  }

  private fun buildReplyAction(peer: String, isGroup: Boolean, notificationId: Int): NotificationCompat.Action {
    val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
      .setLabel("Reply")
      .setAllowFreeFormInput(true)
      .build()

    val intent = Intent(context, ReplyReceiver::class.java).apply {
      putExtra(EXTRA_PEER, peer)
      putExtra(EXTRA_IS_GROUP, isGroup)
      putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }
    val pendingIntent = PendingIntent.getBroadcast(
      context,
      notificationId,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    return NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", pendingIntent)
      .addRemoteInput(remoteInput)
      .setAllowGeneratedReplies(true)
      .build()
  }
}

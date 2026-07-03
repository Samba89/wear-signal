package dev.sam.wearsignal.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.sam.wearsignal.messages.MessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log

/**
 * Receives the text from a Wear notification reply and sends it to the conversation
 * (1:1 or group fan-out).
 */
class ReplyReceiver : BroadcastReceiver() {

  companion object {
    private val TAG = Log.tag(ReplyReceiver::class)
    private const val CHANNEL_ID = "messages"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationPresenter.KEY_REPLY_TEXT)?.toString()
    val peer = intent.getStringExtra(NotificationPresenter.EXTRA_PEER)
    val isGroup = intent.getBooleanExtra(NotificationPresenter.EXTRA_IS_GROUP, false)
    val notificationId = intent.getIntExtra(NotificationPresenter.EXTRA_NOTIFICATION_ID, -1)

    if (replyText.isNullOrBlank() || peer == null) {
      Log.w(TAG, "Missing reply text or peer; ignoring")
      return
    }

    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = MessageSender.send(peer, isGroup, replyText)
        updateNotification(context, notificationId, replyText, result)
      } finally {
        pendingResult.finish()
      }
    }
  }

  private fun updateNotification(context: Context, notificationId: Int, replyText: String, result: MessageSender.Result) {
    if (notificationId < 0) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val text = when (result) {
      is MessageSender.Result.Success -> "You: $replyText"
      is MessageSender.Result.Failure -> "Reply failed: ${result.message}"
    }
    // Replace the action-bearing notification with a quiet confirmation.
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_email)
      .setContentText(text)
      .setWhen(System.currentTimeMillis())
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
    manager.notify(notificationId, notification)
  }
}

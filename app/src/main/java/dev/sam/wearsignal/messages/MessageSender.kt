package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress

/**
 * Sends a 1:1 text reply to a known recipient (the sender of a message we received).
 * Authenticated send (no sealed sender). Synchronous — call from a background thread.
 */
object MessageSender {

  private val TAG = Log.tag(MessageSender::class)

  sealed interface Result {
    data object Success : Result
    data class Failure(val message: String) : Result
  }

  fun sendText(recipientAci: String, body: String): Result {
    if (!AppDeps.account.isLinked) return Result.Failure("Not linked")

    val aci = ACI.parseOrNull(recipientAci) ?: return Result.Failure("Bad recipient")
    val now = System.currentTimeMillis()
    val message = SignalServiceDataMessage.Builder()
      .withTimestamp(now)
      .withBody(body)
      .build()

    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val result = AppDeps.net.messageSender.sendDataMessage(
        SignalServiceAddress(aci),
        null, // authenticated send, no sealed sender
        ContentHint.RESENDABLE,
        message,
        IndividualSendEvents.EMPTY,
        true, // urgent
        false // includePniSignature
      )

      if (result.isSuccess) {
        AppDeps.messages.insert(
          senderAci = recipientAci,
          groupId = null,
          body = body,
          sentAt = now,
          serverAt = now,
          fromSelf = true
        )
        Log.i(TAG, "Reply sent to ${recipientAci.take(8)}")
        Result.Success
      } else {
        Log.w(TAG, "Send unsuccessful: network=${result.isNetworkFailure} unregistered=${result.isUnregisteredFailure} identity=${result.identityFailure != null}")
        Result.Failure("Could not deliver")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Send failed", t)
      Result.Failure(t.message ?: "Send failed")
    } finally {
      webSocket.disconnect()
    }
  }
}

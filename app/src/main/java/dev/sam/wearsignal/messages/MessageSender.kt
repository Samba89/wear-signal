package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents
import org.whispersystems.signalservice.api.SignalServiceMessageSender.LegacyGroupEvents
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import java.util.Optional

/**
 * Sends text messages: 1:1 to a ServiceId, or to a group by fanning out pairwise sends
 * with the groupV2 context (no sender key). After a successful send, a sent transcript
 * goes to our other devices so the phone shows the message too.
 * Authenticated sends (no sealed sender). Synchronous — call from a background thread.
 */
object MessageSender {

  private val TAG = Log.tag(MessageSender::class)

  sealed interface Result {
    data object Success : Result
    data class Failure(val message: String) : Result
  }

  /** Sends to [peer]: a group id (when [isGroup]) or a ServiceId string. */
  fun send(peer: String, isGroup: Boolean, body: String): Result {
    return if (isGroup) sendToGroup(peer, body) else sendText(peer, body)
  }

  /**
   * Sends to [recipient], a ServiceId string — either a bare-UUID ACI (replies, known contacts) or a
   * "PNI:"-prefixed PNI (a contact discovered by number, whose ACI Signal won't reveal until first contact).
   */
  fun sendText(recipient: String, body: String): Result {
    if (!AppDeps.account.isLinked) return Result.Failure("Not linked")

    val serviceId = ServiceId.parseOrNull(recipient) ?: return Result.Failure("Bad recipient")
    val now = System.currentTimeMillis()
    val message = SignalServiceDataMessage.Builder()
      .withTimestamp(now)
      .withBody(body)
      .build()

    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val address = SignalServiceAddress(serviceId)
      val result = AppDeps.net.messageSender.sendDataMessage(
        address,
        null, // authenticated send, no sealed sender
        ContentHint.RESENDABLE,
        message,
        IndividualSendEvents.EMPTY,
        true, // urgent
        false // includePniSignature
      )

      if (result.isSuccess) {
        sendSyncTranscript(message, Optional.of(address), setOf(serviceId))
        storeSent(peer = recipient, groupId = null, body = body, sentAt = now)
        Log.i(TAG, "Message sent to ${recipient.take(12)}")
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

  /** Sends to every cached member of [groupId] (pairwise fan-out with the groupV2 context). */
  fun sendToGroup(groupId: String, body: String): Result {
    if (!AppDeps.account.isLinked) return Result.Failure("Not linked")

    val (masterKeyBytes, revision) = AppDeps.database.readableDatabase.rawQuery(
      "SELECT master_key, revision FROM groups WHERE group_id = ?",
      arrayOf(groupId)
    ).use { cursor ->
      if (!cursor.moveToFirst()) return Result.Failure("Unknown group")
      cursor.getBlob(0) to cursor.getInt(1)
    }

    val memberAcis = GroupStateResolver.cachedMembers(groupId)
      ?: return Result.Failure("Group members not fetched yet — poll first")
    val recipients = memberAcis.mapNotNull { ServiceId.parseOrNull(it) }
    if (recipients.isEmpty()) return Result.Failure("No other members")

    val now = System.currentTimeMillis()
    val groupContext = SignalServiceGroupV2.newBuilder(GroupMasterKey(masterKeyBytes))
      .withRevision(revision)
      .build()
    val message = SignalServiceDataMessage.Builder()
      .withTimestamp(now)
      .withBody(body)
      .asGroupMessage(groupContext)
      .build()

    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val addresses = recipients.map { SignalServiceAddress(it) }
      val results = AppDeps.net.messageSender.sendDataMessage(
        addresses,
        addresses.map { null as SealedSenderAccess? }, // authenticated sends, no sealed sender
        false, // isRecipientUpdate
        ContentHint.RESENDABLE,
        message,
        LegacyGroupEvents.EMPTY,
        null, // partialListener
        null, // cancelationSignal
        true // urgent
      )

      val delivered = results.count { it.isSuccess }
      if (delivered > 0) {
        sendSyncTranscript(message, Optional.empty(), recipients.toSet())
        storeSent(peer = groupId, groupId = groupId, body = body, sentAt = now)
        Log.i(TAG, "Group message sent to $delivered/${results.size} member(s)")
        Result.Success
      } else {
        Log.w(TAG, "Group send failed for all ${results.size} member(s)")
        Result.Failure("Could not deliver")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Group send failed", t)
      Result.Failure(t.message ?: "Send failed")
    } finally {
      webSocket.disconnect()
    }
  }

  /** Tells our other devices (the phone) about the send. Best-effort: the message already went out. */
  private fun sendSyncTranscript(message: SignalServiceDataMessage, destination: Optional<SignalServiceAddress>, recipients: Set<ServiceId>) {
    try {
      val transcript = SentTranscriptMessage(
        destination,
        message.timestamp,
        Optional.of(message),
        0, // expirationStartTimestamp
        recipients.associateWith { false }, // no unidentified delivery
        false, // isRecipientUpdate
        Optional.empty(), // storyMessage
        emptySet(), // storyMessageRecipients
        Optional.empty() // editMessage
      )
      AppDeps.net.messageSender.sendSyncMessage(SignalServiceSyncMessage.forSentTranscript(transcript))
    } catch (t: Throwable) {
      Log.w(TAG, "Sent transcript failed; phone won't show this message", t)
    }
  }

  private fun storeSent(peer: String, groupId: String?, body: String, sentAt: Long) {
    AppDeps.messages.insert(
      peer = peer,
      senderAci = AppDeps.account.aci?.toString() ?: peer,
      groupId = groupId,
      body = body,
      sentAt = sentAt,
      serverAt = sentAt,
      fromSelf = true
    )
  }
}

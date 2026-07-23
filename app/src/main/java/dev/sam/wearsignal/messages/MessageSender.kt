package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents
import org.whispersystems.signalservice.api.SignalServiceMessageSender.LegacyGroupEvents
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage
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
   * Reacts to the message identified by ([targetAuthorAci], [targetSentAt]) — Signal's message
   * key — with [emoji], or retracts our reaction when [remove]. Same fan-out as a text send.
   */
  fun sendReaction(
    peer: String,
    isGroup: Boolean,
    targetAuthorAci: String,
    targetSentAt: Long,
    emoji: String,
    remove: Boolean
  ): Result {
    val targetAuthor = ServiceId.parseOrNull(targetAuthorAci) ?: return Result.Failure("Bad target author")
    val reaction = SignalServiceDataMessage.Reaction(emoji, remove, targetAuthor, targetSentAt)
    return if (isGroup) sendToGroup(peer, body = null, reaction = reaction) else sendText(peer, body = null, reaction = reaction)
  }

  /**
   * Sends to [recipient], a ServiceId string — either a bare-UUID ACI (replies, known contacts) or a
   * "PNI:"-prefixed PNI (a contact discovered by number, whose ACI Signal won't reveal until first contact).
   * The message carries [body], a [reaction], or both.
   */
  fun sendText(recipient: String, body: String?, reaction: SignalServiceDataMessage.Reaction? = null): Result {
    if (!AppDeps.account.isLinked) return Result.Failure("Not linked")

    val serviceId = ServiceId.parseOrNull(recipient) ?: return Result.Failure("Bad recipient")
    val now = System.currentTimeMillis()
    val message = SignalServiceDataMessage.Builder()
      .withTimestamp(now)
      .withBody(body)
      .withReaction(reaction)
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
        storeOwn(peer = recipient, groupId = null, body = body, reaction = reaction, sentAt = now)
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
  fun sendToGroup(groupId: String, body: String?, reaction: SignalServiceDataMessage.Reaction? = null): Result {
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
      .withReaction(reaction)
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
        storeOwn(peer = groupId, groupId = groupId, body = body, reaction = reaction, sentAt = now)
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

  /**
   * Announces that messages were read (their thread was opened on the watch): a read sync to
   * our own devices so the phone clears its unread state, plus — only when [includeReceipts] —
   * READ receipts to the message authors. Best-effort: the reads are already recorded locally,
   * and a failure here just means the rest of Signal learns later (or, for receipts, never).
   */
  fun sendReadSignals(seen: List<SeenMessage>, includeReceipts: Boolean) {
    if (seen.isEmpty() || !AppDeps.account.isLinked) return

    // Incoming senders are always ACIs (PNI-sourced envelopes are dropped), but stay safe.
    val bySender: Map<ACI, List<Long>> = seen
      .mapNotNull { message -> ACI.parseOrNull(message.senderAci)?.let { it to message.sentAt } }
      .groupBy({ it.first }, { it.second })
    if (bySender.isEmpty()) return

    val webSocket = AppDeps.net.authWebSocket
    try {
      webSocket.connect()
      try {
        val reads = bySender.flatMap { (sender, timestamps) -> timestamps.map { ReadMessage(sender, it) } }
        AppDeps.net.messageSender.sendSyncMessage(SignalServiceSyncMessage.forRead(reads))
      } catch (t: Throwable) {
        Log.w(TAG, "Read sync failed; other devices will keep these unread", t)
      }
      if (includeReceipts) {
        val now = System.currentTimeMillis()
        for ((sender, timestamps) in bySender) {
          try {
            AppDeps.net.messageSender.sendReceipt(
              SignalServiceAddress(sender),
              null, // authenticated send, no sealed sender
              SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.READ, timestamps, now),
              false // includePniSignature
            )
          } catch (t: Throwable) {
            Log.w(TAG, "Read receipt to ${sender.toString().take(8)} failed", t)
          }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Read signals failed", t)
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

  /** Records what we just sent: a message row for a text, a reaction row for a reaction. */
  private fun storeOwn(peer: String, groupId: String?, body: String?, reaction: SignalServiceDataMessage.Reaction?, sentAt: Long) {
    val selfAci = AppDeps.account.aci?.toString()
    if (reaction != null) {
      AppDeps.messages.applyReaction(
        peer = peer,
        targetSentAt = reaction.targetSentTimestamp,
        targetAuthorAci = reaction.targetAuthor.toString(),
        reacterAci = selfAci ?: return,
        emoji = reaction.emoji,
        remove = reaction.isRemove
      )
    }
    if (body != null) {
      AppDeps.messages.insert(
        peer = peer,
        senderAci = selfAci ?: peer,
        groupId = groupId,
        body = body,
        sentAt = sentAt,
        serverAt = sentAt,
        fromSelf = true
      )
    }
  }
}

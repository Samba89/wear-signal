package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.whispersystems.signalservice.api.SignalServiceMessageSender.IndividualSendEvents
import org.whispersystems.signalservice.api.SignalServiceMessageSender.LegacyGroupEvents
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.crypto.ContentHint
import org.whispersystems.signalservice.api.crypto.SealedSenderAccess
import org.whispersystems.signalservice.api.messages.AttachmentTransferProgress
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroupV2
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import java.io.File
import java.util.Optional
import java.util.UUID

/**
 * Sends text messages, reactions, and voice notes: 1:1 to a ServiceId, or to a group by fanning
 * out pairwise sends with the groupV2 context (no sender key). After a successful send, a sent
 * transcript goes to our other devices so the phone shows the message too.
 * Authenticated sends (no sealed sender). Synchronous — call from a background thread.
 */
object MessageSender {

  private val TAG = Log.tag(MessageSender::class)

  /**
   * Content type of our voice notes: AAC in an ADTS stream, exactly what Signal-Android's
   * recorder produces and labels, so every Signal client plays it as a voice message.
   */
  const val VOICE_NOTE_CONTENT_TYPE = "audio/aac"

  /** A recorded voice note to upload and attach; [onProgress] gets the upload fraction (0..1). */
  class VoiceNote(val file: File, val onProgress: (Float) -> Unit = {})

  sealed interface Result {
    data object Success : Result
    data class Failure(val message: String) : Result
  }

  /** Sends to [peer]: a group id (when [isGroup]) or a ServiceId string. */
  fun send(peer: String, isGroup: Boolean, body: String): Result {
    return if (isGroup) sendToGroup(peer, body) else sendText(peer, body)
  }

  /**
   * Sends the recording in [file] as a voice message to [peer] (group id when [isGroup]).
   * Uploaded once; groups reuse the one pointer for every member. The caller still owns [file]
   * (kept on failure so the user can retry); on success a copy is stored with our message.
   */
  fun sendVoiceNote(peer: String, isGroup: Boolean, file: File, onProgress: (Float) -> Unit = {}): Result {
    if (!file.exists() || file.length() == 0L) return Result.Failure("Empty recording")
    val voiceNote = VoiceNote(file, onProgress)
    return if (isGroup) {
      sendToGroup(peer, body = null, voiceNote = voiceNote)
    } else {
      sendText(peer, body = null, voiceNote = voiceNote)
    }
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
   * The message carries [body], a [reaction], a [voiceNote], or a combination.
   */
  fun sendText(
    recipient: String,
    body: String?,
    reaction: SignalServiceDataMessage.Reaction? = null,
    voiceNote: VoiceNote? = null
  ): Result {
    if (!AppDeps.account.isLinked) return Result.Failure("Not linked")

    val serviceId = ServiceId.parseOrNull(recipient) ?: return Result.Failure("Bad recipient")
    val now = System.currentTimeMillis()

    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val message = SignalServiceDataMessage.Builder()
        .withTimestamp(now)
        .withBody(body)
        .withReaction(reaction)
        .withAttachments(voiceNote?.let { listOf(uploadVoiceNote(it)) })
        .build()
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
        storeOwn(peer = recipient, groupId = null, body = body, reaction = reaction, voiceNote = voiceNote?.file, sentAt = now)
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

  /**
   * Sends to every cached member of [groupId] (pairwise fan-out with the groupV2 context).
   * A [voiceNote] is uploaded once and the same pointer goes to every member.
   */
  fun sendToGroup(
    groupId: String,
    body: String?,
    reaction: SignalServiceDataMessage.Reaction? = null,
    voiceNote: VoiceNote? = null
  ): Result {
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

    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val message = SignalServiceDataMessage.Builder()
        .withTimestamp(now)
        .withBody(body)
        .withReaction(reaction)
        .withAttachments(voiceNote?.let { listOf(uploadVoiceNote(it)) })
        .asGroupMessage(groupContext)
        .build()
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
        storeOwn(peer = groupId, groupId = groupId, body = body, reaction = reaction, voiceNote = voiceNote?.file, sentAt = now)
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

  /**
   * Encrypts and uploads [voiceNote] to the CDN, returning the pointer to put in the message.
   * Needs the auth websocket connected (the upload form is fetched over it); the bytes then go
   * straight to the CDN over HTTPS. Throws on failure.
   */
  private fun uploadVoiceNote(voiceNote: VoiceNote): SignalServiceAttachmentPointer {
    val length = voiceNote.file.length()
    val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(length))
    val form = AppDeps.net.attachmentApi.getAttachmentV4UploadForm(ciphertextLength).successOrThrow()
    val spec = AppDeps.net.attachmentApi.getResumableUploadSpec(form)

    val listener = object : SignalServiceAttachment.ProgressListener {
      override fun onAttachmentProgress(progress: AttachmentTransferProgress) = voiceNote.onProgress(progress.value)
      override fun shouldCancel(): Boolean = false
    }

    voiceNote.file.inputStream().use { input ->
      val stream = SignalServiceAttachment.newStreamBuilder()
        .withStream(input)
        .withContentType(VOICE_NOTE_CONTENT_TYPE)
        .withLength(length)
        .withVoiceNote(true)
        .withUploadTimestamp(System.currentTimeMillis())
        .withResumableUploadSpec(spec)
        .withUuid(UUID.randomUUID())
        .withListener(listener)
        .build()
      val pointer = AppDeps.net.messageSender.uploadAttachment(stream)
      Log.i(TAG, "Voice note uploaded to cdn${pointer.cdnNumber} (${length / 1024} KB)")
      return pointer
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

  /**
   * Records what we just sent: a message row for a text or voice note, a reaction row for a
   * reaction. A [voiceNote] is copied into the attachments dir so the thread can play it back.
   */
  private fun storeOwn(
    peer: String,
    groupId: String?,
    body: String?,
    reaction: SignalServiceDataMessage.Reaction?,
    voiceNote: File? = null,
    sentAt: Long
  ) {
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
    if (body != null || voiceNote != null) {
      val senderAci = selfAci ?: peer
      val inserted = AppDeps.messages.insert(
        peer = peer,
        senderAci = senderAci,
        groupId = groupId,
        body = body ?: "",
        sentAt = sentAt,
        serverAt = sentAt,
        fromSelf = true,
        attachmentType = if (voiceNote != null) VOICE_NOTE_CONTENT_TYPE else null,
        attachmentVoice = voiceNote != null
      )
      if (inserted && voiceNote != null) {
        storeOwnVoiceNote(peer, senderAci, sentAt, voiceNote)
      }
    }
  }

  /** Copies our sent recording to `attachments/att_<messageId>.aac` and points the message row at it. */
  private fun storeOwnVoiceNote(peer: String, senderAci: String, sentAt: Long, recording: File) {
    try {
      val db = AppDeps.database.writableDatabase
      val messageId = db.rawQuery(
        "SELECT _id FROM messages WHERE peer = ? AND sender_aci = ? AND sent_at = ? LIMIT 1",
        arrayOf(peer, senderAci, sentAt.toString())
      ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else return }

      val dir = File(AppDeps.context.filesDir, "attachments").apply { mkdirs() }
      val file = File(dir, "att_$messageId.aac")
      recording.copyTo(file, overwrite = true)
      db.execSQL("UPDATE messages SET attachment_path = ? WHERE _id = ?", arrayOf(file.path, messageId))
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't keep a local copy of the sent voice note", t)
    }
  }
}

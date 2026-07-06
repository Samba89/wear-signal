package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.BuildConfig
import dev.sam.wearsignal.crypto.SessionLock
import org.signal.core.models.ServiceId
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.api.crypto.SignalGroupSessionBuilder
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher
import org.whispersystems.signalservice.api.crypto.SignalServiceCipherResult
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope
import org.whispersystems.signalservice.internal.push.ReceiptMessage

/**
 * Decrypts envelopes and turns DataMessages / sent-transcripts into stored messages.
 * Never throws: undecryptable or unwanted envelopes are logged and dropped (then acked
 * by the caller) so a poison message can't wedge the queue.
 */
class EnvelopeProcessor(private val messages: MessagesRepository) {

  companion object {
    private val TAG = Log.tag(EnvelopeProcessor::class)
  }

  private val certificateValidator: CertificateValidator by lazy {
    val roots = BuildConfig.UNIDENTIFIED_SENDER_TRUST_ROOTS.map { ECPublicKey(Base64.decode(it)) }
    CertificateValidator(ArrayList(roots))
  }

  data class Processed(
    val newMessages: List<IncomingMessage>
  )

  data class IncomingMessage(
    /** Conversation key: group id for groups, the other party's ACI for 1:1. */
    val peer: String,
    val senderAci: String,
    val groupId: String?,
    val body: String,
    val sentAt: Long,
    val fromSelf: Boolean
  )

  fun process(envelope: Envelope, serverDeliveredTimestamp: Long): IncomingMessage? {
    return try {
      processOrThrow(envelope, serverDeliveredTimestamp)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to process envelope type=${envelope.type} ts=${envelope.clientTimestamp}; dropping", t)
      null
    }
  }

  private fun processOrThrow(envelope: Envelope, serverDeliveredTimestamp: Long): IncomingMessage? {
    val account = AppDeps.account
    val selfAci = account.aci ?: return null
    val selfPni = account.pni

    if (envelope.type == Envelope.Type.SERVER_DELIVERY_RECEIPT) {
      // Server-generated delivery receipt: the envelope timestamp is the sent timestamp
      // of our message that reached the recipient's device.
      envelope.clientTimestamp?.let { markReceipts(listOf(it), read = false) }
      return null
    }

    val destination: ServiceId = ServiceId.parseOrNull(envelope.destinationServiceId, envelope.destinationServiceIdBinary) ?: return null
    if (destination != selfAci && destination != selfPni) {
      Log.w(TAG, "Envelope for unknown destination, ignoring")
      return null
    }

    val store = if (destination == selfPni) AppDeps.pniProtocolStore else AppDeps.aciProtocolStore
    val localAddress = SignalServiceAddress(selfAci, account.e164)
    val cipher = SignalServiceCipher(localAddress, account.deviceId, store, SessionLock, certificateValidator)

    val result: SignalServiceCipherResult = cipher.decrypt(envelope, serverDeliveredTimestamp) ?: return null
    val content: Content = result.content

    // Sender key distribution must be processed before anything else from this sender.
    content.senderKeyDistributionMessage?.let { skdmBytes ->
      val sender = SignalProtocolAddress(result.metadata.sourceServiceId.toString(), result.metadata.sourceDeviceId)
      val skdm = SenderKeyDistributionMessage(skdmBytes.toByteArray())
      Log.i(TAG, "Processing SKDM for distribution ${skdm.distributionId}")
      SignalGroupSessionBuilder(SessionLock, GroupSessionBuilder(AppDeps.aciProtocolStore)).process(sender, skdm)
    }

    content.receiptMessage?.let { receipt ->
      when (receipt.type) {
        ReceiptMessage.Type.DELIVERY -> markReceipts(receipt.timestamp, read = false)
        ReceiptMessage.Type.READ, ReceiptMessage.Type.VIEWED -> markReceipts(receipt.timestamp, read = true)
        null -> Unit
      }
      return null
    }

    val sourceServiceId = result.metadata.sourceServiceId
    if (sourceServiceId is PNI) {
      return null
    }

    content.dataMessage?.let { data ->
      harvestProfileKey(sourceServiceId, data)
      val body = data.body
      if (body.isNullOrEmpty()) {
        return null
      }
      val groupId = data.groupV2?.let { recordGroup(it.masterKey!!.toByteArray(), it.revision ?: 0) }
      return IncomingMessage(
        peer = groupId ?: sourceServiceId.toString(),
        senderAci = sourceServiceId.toString(),
        groupId = groupId,
        body = body,
        sentAt = data.timestamp ?: envelope.clientTimestamp ?: serverDeliveredTimestamp,
        fromSelf = false
      )
    }

    content.syncMessage?.sent?.let { sent ->
      val data = sent.message ?: return null
      val body = data.body
      if (body.isNullOrEmpty()) {
        return null
      }
      val groupId = data.groupV2?.let { recordGroup(it.masterKey!!.toByteArray(), it.revision ?: 0) }
      val destination = ServiceId.parseOrNull(sent.destinationServiceId, null)?.toString()
      val peer = groupId ?: destination
      if (peer == null) {
        Log.w(TAG, "Sent transcript with no group or destination; dropping")
        return null
      }
      return IncomingMessage(
        peer = peer,
        senderAci = selfAci.toString(),
        groupId = groupId,
        body = body,
        sentAt = sent.timestamp ?: serverDeliveredTimestamp,
        fromSelf = true
      )
    }

    return null
  }

  /** Store an IncomingMessage and return whether it should notify (own sent messages shouldn't). */
  fun store(message: IncomingMessage) {
    messages.insert(
      peer = message.peer,
      senderAci = message.senderAci,
      groupId = message.groupId,
      body = message.body,
      sentAt = message.sentAt,
      serverAt = System.currentTimeMillis(),
      fromSelf = message.fromSelf
    )
  }

  /**
   * Marks our sent messages (matched by sent timestamp) delivered or read.
   * Read implies delivered. Group receipts from any member count.
   */
  private fun markReceipts(sentTimestamps: List<Long>, read: Boolean) {
    if (sentTimestamps.isEmpty()) return
    val db = AppDeps.database.writableDatabase
    val now = System.currentTimeMillis()
    for (sentAt in sentTimestamps) {
      if (read) {
        db.execSQL(
          "UPDATE messages SET read_at = CASE WHEN read_at = 0 THEN ? ELSE read_at END, " +
            "delivered_at = CASE WHEN delivered_at = 0 THEN ? ELSE delivered_at END " +
            "WHERE from_self = 1 AND sent_at = ?",
          arrayOf(now, now, sentAt)
        )
      } else {
        db.execSQL(
          "UPDATE messages SET delivered_at = CASE WHEN delivered_at = 0 THEN ? ELSE delivered_at END " +
            "WHERE from_self = 1 AND sent_at = ?",
          arrayOf(now, sentAt)
        )
      }
    }
  }

  private fun harvestProfileKey(sender: ServiceId, data: DataMessage) {
    if (sender !is ACI) return
    val profileKey = data.profileKey ?: return
    val values = android.content.ContentValues().apply {
      put("aci", sender.toString())
      put("profile_key", profileKey.toByteArray())
    }
    val db = AppDeps.database.writableDatabase
    val updated = db.update("contacts", values, "aci = ?", arrayOf(sender.toString()))
    if (updated == 0) {
      db.insert("contacts", null, values)
    }
  }

  /**
   * Derives the group id and upserts the master key + latest revision into the groups table,
   * so GroupStateResolver can later fetch the title and member list.
   */
  private fun recordGroup(masterKey: ByteArray, revision: Int): String {
    val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
    val groupId = Base64.encodeWithPadding(secretParams.publicParams.groupIdentifier.serialize())

    val db = AppDeps.database.writableDatabase
    val values = android.content.ContentValues().apply {
      put("group_id", groupId)
      put("master_key", masterKey)
    }
    val inserted = db.insertWithOnConflict("groups", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    if (inserted == -1L) {
      db.execSQL("UPDATE groups SET revision = MAX(revision, ?) WHERE group_id = ?", arrayOf(revision, groupId))
    } else {
      db.execSQL("UPDATE groups SET revision = ? WHERE group_id = ?", arrayOf(revision, groupId))
    }
    return groupId
  }
}

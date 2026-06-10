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
      return IncomingMessage(
        senderAci = sourceServiceId.toString(),
        groupId = data.groupV2?.masterKey?.let { deriveGroupId(it.toByteArray()) },
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
      return IncomingMessage(
        senderAci = selfAci.toString(),
        groupId = data.groupV2?.masterKey?.let { deriveGroupId(it.toByteArray()) },
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
      senderAci = message.senderAci,
      groupId = message.groupId,
      body = message.body,
      sentAt = message.sentAt,
      serverAt = System.currentTimeMillis(),
      fromSelf = message.fromSelf
    )
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

  private fun deriveGroupId(masterKey: ByteArray): String {
    val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
    return Base64.encodeWithPadding(secretParams.publicParams.groupIdentifier.serialize())
  }
}

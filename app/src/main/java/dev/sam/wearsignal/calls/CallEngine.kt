package dev.sam.wearsignal.calls

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager
import org.signal.ringrtc.CallSummary
import org.signal.ringrtc.CameraControl
import org.signal.ringrtc.HttpHeader
import org.signal.ringrtc.NetworkRoute
import org.signal.ringrtc.Remote
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.VideoSink
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage
import org.whispersystems.signalservice.api.messages.calls.BusyMessage
import org.whispersystems.signalservice.api.messages.calls.CallingResponse
import org.whispersystems.signalservice.api.messages.calls.HangupMessage
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage
import org.whispersystems.signalservice.api.messages.calls.OfferMessage
import org.whispersystems.signalservice.api.messages.calls.OpaqueMessage
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.internal.push.CallMessage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** What the call UI renders. Peers are ACI strings; names resolve at display time. */
sealed interface CallState {
  data object Idle : CallState
  data class Outgoing(val peer: String, val video: Boolean, val ringing: Boolean) : CallState
  data class Incoming(val peer: String, val video: Boolean) : CallState
  data class Active(val peer: String, val video: Boolean, val connectedAt: Long, val muted: Boolean) : CallState
  data class Ended(val peer: String, val reason: String) : CallState
}

/**
 * The RingRTC bridge and 1:1 call state machine. Audio-only on the watch: video calls
 * are answered with the camera permanently off (the other side sees your avatar) and
 * incoming video frames are never rendered.
 *
 * Live signaling only works while [CallSignaling] holds the websocket open — during a
 * session the engine consumes CallMessages routed from EnvelopeProcessor; at all other
 * times drained call messages fall through to [CallLog] as history.
 */
object CallEngine : CallManager.Observer {

  private val TAG = Log.tag(CallEngine::class)

  /** RingRTC rejects offers older than 60s; leave headroom for clock slop and setup. */
  private const val MAX_LIVE_OFFER_AGE_SEC = 45L

  private val stateFlow = MutableStateFlow<CallState>(CallState.Idle)
  val state: StateFlow<CallState> = stateFlow.asStateFlow()

  /** Set the instant a session begins (before RingRTC's first callback) so the drain
   *  loop knows not to tear down the websocket underneath a starting call. */
  @Volatile
  private var sessionStarted = false

  /** Bumped on every session start; lets the delayed teardown detect it went stale. */
  @Volatile
  private var sessionGeneration = 0

  val isSessionActive: Boolean get() = sessionStarted || stateFlow.value != CallState.Idle

  @Volatile
  private var callManager: CallManager? = null

  /** RingRTC callbacks arrive on its worker thread; our network work moves here. */
  private val executor = Executors.newSingleThreadScheduledExecutor()
  private val httpExecutor = Executors.newSingleThreadExecutor()

  // Per-call state, owned by RingRTC callbacks + executor.
  @Volatile private var currentCallId: CallId? = null
  @Volatile private var currentPeer: RemotePeer? = null
  @Volatile private var currentVideo = false
  @Volatile private var currentOutgoing = false
  @Volatile private var currentMuted = false
  @Volatile private var wasConnected = false
  @Volatile private var callStartedAt = 0L

  private val eglBase: EglBase by lazy { EglBase.create() }
  private val dropFramesSink = VideoSink { } // remote video decoded but never rendered

  /**
   * A camera that exists but never yields a frame. RingRTC only builds the local video
   * track — and only then adds a video transceiver, which is what puts the m=video
   * section in the offer — when hasCapturer() is true. Reporting no capturer produces
   * an audio-only SDP, and a Signal peer can't negotiate that: it drops the call and
   * files it as missed without ever ringing (a Signal "audio call" is really a video
   * call that starts camera-off, which is why video can be switched on mid-call).
   *
   * So we claim a capturer, accept the observer and never feed it, and never enable
   * video — which is exactly what a camera-off call looks like on the wire.
   */
  private val darkCamera = object : CameraControl {
    override fun hasCapturer(): Boolean = true
    override fun initCapturer(observer: CapturerObserver) = Unit
    override fun setEnabled(enable: Boolean) = Unit
    override fun flip() = Unit
    override fun setOrientation(orientation: Int?) = Unit
  }

  private var audioFocusRequest: AudioFocusRequest? = null

  private val ringRtcLogger = object : org.signal.ringrtc.Log.Logger {
    override fun v(tag: String, message: String?, throwable: Throwable?) = Log.v(tag, message, throwable)
    override fun d(tag: String, message: String?, throwable: Throwable?) = Log.d(tag, message, throwable)
    override fun i(tag: String, message: String?, throwable: Throwable?) = Log.i(tag, message, throwable)
    override fun w(tag: String, message: String?, throwable: Throwable?) = Log.w(tag, message, throwable)
    override fun e(tag: String, message: String?, throwable: Throwable?) = Log.e(tag, message, throwable)
  }

  /** Lazily initializes RingRTC (loads the native library) and creates the CallManager. */
  @Synchronized
  fun manager(): CallManager {
    callManager?.let { return it }
    CallManager.initialize(AppDeps.context, ringRtcLogger, emptyMap())
    val created = CallManager.createCallManager(this) ?: throw IllegalStateException("createCallManager returned null")
    AppDeps.account.aci?.rawUuid?.let { created.setSelfUuid(it) }
    callManager = created
    return created
  }

  // region Public call control (safe to call from any thread)

  /** Places an audio call to [peerAci]. The UI observes [state] for progress. */
  fun startOutgoing(peerAci: String) {
    executor.execute {
      if (isSessionActive) return@execute
      val remote = RemotePeer(peerAci)
      sessionStarted = true
      sessionGeneration += 1
      currentPeer = remote
      currentVideo = false
      currentOutgoing = true
      currentMuted = false
      wasConnected = false
      callStartedAt = System.currentTimeMillis()
      stateFlow.value = CallState.Outgoing(peerAci, video = false, ringing = false)
      CallSessionService.start(AppDeps.context)
      CallSignaling.start()
      try {
        // Connect synchronously here: the TURN fetch and offer send that follow
        // onStartCall need the socket before the signaling thread may have it up.
        AppDeps.net.authWebSocket.connect()
        manager().call(remote, CallManager.CallMediaType.AUDIO_CALL, AppDeps.account.deviceId)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to start outgoing call", t)
        concludeToIdle("Couldn't start call")
      }
    }
  }

  fun accept() {
    executor.execute {
      val callId = currentCallId ?: return@execute
      try {
        manager().acceptCall(callId)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to accept call", t)
      }
    }
  }

  /** Ends the current call at any stage (decline while ringing, hang up while active). */
  fun hangup() {
    executor.execute {
      try {
        manager().hangup()
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to hang up", t)
        concludeToIdle("Call failed")
      }
    }
  }

  fun toggleMute() {
    executor.execute {
      val active = stateFlow.value as? CallState.Active ?: return@execute
      currentMuted = !currentMuted
      try {
        manager().setAudioEnable(!currentMuted)
        stateFlow.value = active.copy(muted = currentMuted)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to toggle mute", t)
      }
    }
  }

  // endregion

  // region Live signaling intake (called from EnvelopeProcessor)

  /**
   * Routes a drained CallMessage into RingRTC when it can matter live: any message
   * while a session runs, or a fresh offer that can still ring. Returns false when the
   * message is history — the caller records it in [CallLog] instead.
   */
  fun maybeHandleLive(senderAci: String, senderDeviceId: Int, sentAt: Long, ageSec: Long, call: CallMessage): Boolean {
    val active = isSessionActive
    val offer = call.offer
    val offerId = offer?.id

    if (offer != null) {
      val opaque = offer.opaque
      if (offerId == null || opaque == null || ageSec > MAX_LIVE_OFFER_AGE_SEC) return false
      if (!active && !ringableNow()) return false
      val keys = identityKeys(senderAci, senderDeviceId) ?: return false
      val secondCaller = active && currentPeer?.aci != senderAci
      if (secondCaller) {
        // RingRTC auto-busies them; still leave a missed-call row behind.
        CallLog.handleCallMessage(senderAci, sentAt, call)
      }
      val startedSession = !active
      if (startedSession) {
        sessionStarted = true
        sessionGeneration += 1
        currentPeer = RemotePeer(senderAci)
        currentVideo = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL
        currentOutgoing = false
        currentMuted = false
        wasConnected = false
        callStartedAt = System.currentTimeMillis()
        CallSessionService.start(AppDeps.context)
        CallSignaling.start()
      }
      val mediaType = if (offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL) {
        CallManager.CallMediaType.VIDEO_CALL
      } else {
        CallManager.CallMediaType.AUDIO_CALL
      }
      val routed = try {
        manager().receivedOffer(
          CallId(offerId),
          RemotePeer(senderAci),
          senderDeviceId,
          opaque.toByteArray(),
          ageSec,
          mediaType,
          AppDeps.account.deviceId,
          keys.first,
          keys.second
        )
        true
      } catch (t: Throwable) {
        Log.w(TAG, "receivedOffer failed", t)
        false
      }
      if (!routed) {
        // The engine never took the call: unwind the session we just opened and let
        // the caller record the offer as history instead.
        if (startedSession) {
          concludeToIdle(null)
        }
        return false
      }
      return true
    }

    if (!active) return false
    val remote = RemotePeer(senderAci)

    call.answer?.let { answer ->
      val answerId = answer.id ?: return false
      val keys = identityKeys(senderAci, senderDeviceId) ?: return false
      return runRingRtc("receivedAnswer") {
        manager().receivedAnswer(CallId(answerId), remote, senderDeviceId, answer.opaque!!.toByteArray(), keys.first, keys.second)
      }
    }

    if (call.iceUpdate.isNotEmpty()) {
      val byCall = call.iceUpdate.filter { it.id != null && it.opaque != null }.groupBy { it.id!! }
      for ((id, updates) in byCall) {
        runRingRtc("receivedIceCandidates") {
          manager().receivedIceCandidates(CallId(id), remote, senderDeviceId, updates.map { it.opaque!!.toByteArray() })
        }
      }
      return true
    }

    call.hangup?.let { hangup ->
      val hangupId = hangup.id ?: return false
      val type = when (hangup.type) {
        CallMessage.Hangup.Type.HANGUP_ACCEPTED -> CallManager.HangupType.ACCEPTED
        CallMessage.Hangup.Type.HANGUP_DECLINED -> CallManager.HangupType.DECLINED
        CallMessage.Hangup.Type.HANGUP_BUSY -> CallManager.HangupType.BUSY
        CallMessage.Hangup.Type.HANGUP_NEED_PERMISSION -> CallManager.HangupType.NEED_PERMISSION
        else -> CallManager.HangupType.NORMAL
      }
      return runRingRtc("receivedHangup") {
        manager().receivedHangup(CallId(hangupId), remote, senderDeviceId, type, hangup.deviceId ?: 0)
      }
    }

    call.busy?.let { busy ->
      val busyId = busy.id ?: return false
      return runRingRtc("receivedBusy") {
        manager().receivedBusy(CallId(busyId), remote, senderDeviceId)
      }
    }

    // Opaque messages carry group-call signaling; group calls aren't supported.
    return call.opaque != null
  }

  /** A live ring needs the mic permission and a linked account. */
  private fun ringableNow(): Boolean {
    val granted = AppDeps.context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) Log.w(TAG, "Fresh offer but no mic permission; logging as missed")
    return granted && AppDeps.account.isLinked
  }

  private fun runRingRtc(what: String, block: () -> Unit): Boolean {
    return try {
      block()
      true
    } catch (t: Throwable) {
      Log.w(TAG, "$what failed", t)
      true // consumed either way; RingRTC will conclude the call on its own errors
    }
  }

  /** Raw 32-byte public identity keys (remote, local) as RingRTC wants them. */
  private fun identityKeys(senderAci: String, senderDeviceId: Int): Pair<ByteArray, ByteArray>? {
    val remote = AppDeps.aciProtocolStore.getIdentity(SignalProtocolAddress(senderAci, senderDeviceId))
      ?.publicKey?.publicKeyBytes
    val local = AppDeps.account.aciIdentityKeyPair?.publicKey?.publicKey?.publicKeyBytes
    if (remote == null || local == null) {
      Log.w(TAG, "Missing identity key for call (remote=${remote != null})")
      return null
    }
    return Pair(remote, local)
  }

  // endregion

  // region RingRTC observer: call lifecycle

  override fun onStartCall(remote: Remote?, callId: CallId?, isOutgoing: Boolean?, callMediaType: CallManager.CallMediaType?) {
    val peer = (remote as? RemotePeer) ?: return
    currentCallId = callId
    if (isOutgoing == false) {
      stateFlow.value = CallState.Incoming(peer.aci, currentVideo)
    }
    // Proceed immediately (Signal does the same): ICE gathers while the user decides.
    executor.execute {
      val id = callId ?: return@execute
      try {
        val servers = iceServers()
        manager().proceed(
          id,
          AppDeps.context,
          eglBase,
          org.signal.ringrtc.AudioConfig(),
          dropFramesSink,
          dropFramesSink,
          darkCamera,
          servers,
          false, // hideIp: allow direct connections, like Signal with known contacts
          // Video is negotiated but never rendered, so keep whatever the peer sends if
          // they enable their camera down to the cheapest stream they'll produce.
          CallManager.DataMode.LOW,
          null, // no audio level callbacks
          null, // default DRED duration
          false, // no software VP9: costly to decode on a watch, and never displayed
          false // never enable the camera we don't have
        )
      } catch (t: Throwable) {
        Log.w(TAG, "proceed() failed", t)
        try {
          manager().drop(id)
        } catch (_: Throwable) {
        }
        concludeToIdle("Couldn't connect")
      }
    }
  }

  private fun iceServers(): List<PeerConnection.IceServer> {
    return CallingApi.turnServers().flatMap { info ->
      val withIps = info.urlsWithIps.orEmpty().map { url ->
        PeerConnection.IceServer.builder(url)
          .setUsername(info.username.orEmpty())
          .setPassword(info.password.orEmpty())
          .setHostname(info.hostname)
          .createIceServer()
      }
      val byName = info.urls.orEmpty().map { url ->
        PeerConnection.IceServer.builder(url)
          .setUsername(info.username.orEmpty())
          .setPassword(info.password.orEmpty())
          .createIceServer()
      }
      withIps + byName
    }
  }

  /** RingRTC callbacks are per-call: a busied second caller's events must not touch the active call. */
  private fun isActiveCall(remote: Remote?): Boolean {
    val current = currentPeer ?: return false
    return (remote as? RemotePeer)?.recipientEquals(current) == true
  }

  override fun onCallEvent(remote: Remote?, event: CallManager.CallEvent?) {
    Log.i(TAG, "onCallEvent: $event")
    if (!isActiveCall(remote)) {
      Log.i(TAG, "Event for a non-active call; ignoring")
      return
    }
    when (event) {
      CallManager.CallEvent.REMOTE_RINGING -> {
        (stateFlow.value as? CallState.Outgoing)?.let { stateFlow.value = it.copy(ringing = true) }
      }
      CallManager.CallEvent.LOCAL_RINGING -> Unit // state is already Incoming; service is ringing
      CallManager.CallEvent.LOCAL_CONNECTED, CallManager.CallEvent.REMOTE_CONNECTED -> onConnected()
      CallManager.CallEvent.RECEIVED_OFFER_EXPIRED -> {
        // Rang too late after all — record it as missed; RingRTC concludes the call.
        recordCurrentCall(CallLog.OUTCOME_MISSED)
      }
      else -> Unit
    }
  }

  private fun onConnected() {
    if (wasConnected) return
    wasConnected = true
    val peer = currentPeer?.aci ?: return
    executor.execute {
      try {
        manager().setAudioEnable(!currentMuted)
      } catch (t: Throwable) {
        Log.w(TAG, "setAudioEnable failed", t)
      }
    }
    startAudio()
    stateFlow.value = CallState.Active(peer, currentVideo, System.currentTimeMillis(), currentMuted)
  }

  override fun onCallEnded(remote: Remote?, reason: CallManager.CallEndReason, summary: CallSummary) {
    Log.i(TAG, "onCallEnded: $reason")
    if (!isActiveCall(remote)) {
      Log.i(TAG, "Ended a non-active call; ignoring")
      return
    }
    val outcome = when {
      wasConnected -> CallLog.OUTCOME_ANSWERED
      reason == CallManager.CallEndReason.REMOTE_HANGUP_ACCEPTED -> CallLog.OUTCOME_ANSWERED // on our phone
      reason == CallManager.CallEndReason.LOCAL_HANGUP && !currentOutgoing -> CallLog.OUTCOME_DECLINED
      reason == CallManager.CallEndReason.REMOTE_HANGUP_DECLINED -> CallLog.OUTCOME_DECLINED
      else -> CallLog.OUTCOME_MISSED
    }
    recordCurrentCall(outcome)
    val text = when {
      wasConnected -> "Call ended"
      reason == CallManager.CallEndReason.REMOTE_HANGUP_ACCEPTED -> "Answered on another device"
      currentOutgoing -> "No answer"
      else -> "Missed call"
    }
    currentPeer?.let { stateFlow.value = CallState.Ended(it.aci, text) }
    stopAudio()
  }

  override fun onCallConcluded(remote: Remote?) {
    // A concluded secondary call (auto-busied) must not tear down the active one.
    if (currentPeer != null && !isActiveCall(remote)) {
      Log.i(TAG, "Concluded a non-active call; ignoring")
      return
    }
    concludeToIdle(null)
  }

  private fun concludeToIdle(reason: String?) {
    stopAudio()
    val peer = currentPeer?.aci
    currentCallId = null
    currentPeer = null
    wasConnected = false
    if (reason != null && peer != null) {
      stateFlow.value = CallState.Ended(peer, reason)
    }
    // Give the Ended screen a moment, then release everything — unless a new session
    // started meanwhile (its generation bump makes this teardown stale).
    val generation = sessionGeneration
    executor.schedule({
      if (generation == sessionGeneration) {
        sessionStarted = false
        stateFlow.value = CallState.Idle
        CallSignaling.stop()
        CallSessionService.stop(AppDeps.context)
      }
    }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  private fun recordCurrentCall(outcome: String) {
    val peer = currentPeer?.aci ?: return
    val callId = currentCallId?.longValue() ?: return
    CallLog.recordLiveCall(callId, peer, currentVideo, currentOutgoing, outcome, callStartedAt)
  }

  // endregion

  // region RingRTC observer: outbound signaling

  private fun destinationDeviceId(broadcast: Boolean?, remoteDeviceId: Int?): Int? =
    if (broadcast == true) null else remoteDeviceId

  private fun sendSignaling(callId: CallId?, peerAci: String, message: SignalServiceCallMessage) {
    executor.execute {
      try {
        val address = SignalServiceAddress(ServiceId.parseOrThrow(peerAci))
        AppDeps.net.messageSender.sendCallMessage(address, null, message)
        callId?.let { manager().messageSent(it) }
      } catch (t: Throwable) {
        Log.w(TAG, "Call signaling send failed", t)
        callId?.let {
          try {
            manager().messageSendFailure(it)
          } catch (_: Throwable) {
          }
        }
      }
    }
  }

  override fun onSendOffer(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, opaque: ByteArray, callMediaType: CallManager.CallMediaType?) {
    val peer = (remote as? RemotePeer)?.aci ?: return
    val id = callId ?: return
    val type = if (callMediaType == CallManager.CallMediaType.VIDEO_CALL) OfferMessage.Type.VIDEO_CALL else OfferMessage.Type.AUDIO_CALL
    sendSignaling(id, peer, SignalServiceCallMessage.forOffer(OfferMessage(id.longValue(), type, opaque), destinationDeviceId(broadcast, remoteDeviceId)))
  }

  override fun onSendAnswer(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, opaque: ByteArray) {
    val peer = (remote as? RemotePeer)?.aci ?: return
    val id = callId ?: return
    sendSignaling(id, peer, SignalServiceCallMessage.forAnswer(AnswerMessage(id.longValue(), opaque), destinationDeviceId(broadcast, remoteDeviceId)))
  }

  override fun onSendIceCandidates(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, iceCandidates: List<ByteArray>?) {
    val peer = (remote as? RemotePeer)?.aci ?: return
    val id = callId ?: return
    val updates = iceCandidates.orEmpty().map { IceUpdateMessage(id.longValue(), it) }
    if (updates.isEmpty()) return
    sendSignaling(id, peer, SignalServiceCallMessage.forIceUpdates(updates, destinationDeviceId(broadcast, remoteDeviceId)))
  }

  override fun onSendHangup(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?, hangupType: CallManager.HangupType?, deviceId: Int?) {
    val peer = (remote as? RemotePeer)?.aci ?: return
    val id = callId ?: return
    val type = when (hangupType) {
      CallManager.HangupType.ACCEPTED -> HangupMessage.Type.ACCEPTED
      CallManager.HangupType.DECLINED -> HangupMessage.Type.DECLINED
      CallManager.HangupType.BUSY -> HangupMessage.Type.BUSY
      CallManager.HangupType.NEED_PERMISSION -> HangupMessage.Type.NEED_PERMISSION
      else -> HangupMessage.Type.NORMAL
    }
    sendSignaling(id, peer, SignalServiceCallMessage.forHangup(HangupMessage(id.longValue(), type, deviceId ?: 0), destinationDeviceId(broadcast, remoteDeviceId)))
  }

  override fun onSendBusy(callId: CallId?, remote: Remote?, remoteDeviceId: Int?, broadcast: Boolean?) {
    val peer = (remote as? RemotePeer)?.aci ?: return
    val id = callId ?: return
    sendSignaling(id, peer, SignalServiceCallMessage.forBusy(BusyMessage(id.longValue()), destinationDeviceId(broadcast, remoteDeviceId)))
  }

  override fun onSendCallMessage(recipientUuid: UUID, message: ByteArray, urgency: CallManager.CallMessageUrgency) {
    val urgencyMapped = if (urgency == CallManager.CallMessageUrgency.HANDLE_IMMEDIATELY) {
      OpaqueMessage.Urgency.HANDLE_IMMEDIATELY
    } else {
      OpaqueMessage.Urgency.DROPPABLE
    }
    sendSignaling(null, recipientUuid.toString(), SignalServiceCallMessage.forOpaque(OpaqueMessage(message, urgencyMapped), null))
  }

  override fun onSendCallMessageToGroup(groupId: ByteArray, message: ByteArray, urgency: CallManager.CallMessageUrgency, overrideRecipients: List<UUID>) {
    Log.w(TAG, "Group call signaling not supported; dropping")
  }

  override fun onSendCallMessageToAdhocGroup(message: ByteArray, urgency: CallManager.CallMessageUrgency, expiration: Instant?, recipientsToEndorsements: Map<UUID, ByteArray>) = Unit

  // endregion

  // region RingRTC observer: environment

  /** RingRTC does its SFU HTTP through us; relay via the service socket and feed back. */
  override fun onSendHttpRequest(requestId: Long, url: String, method: CallManager.HttpMethod, headers: List<HttpHeader>?, body: ByteArray?) {
    httpExecutor.execute {
      val response = try {
        AppDeps.net.authPushServiceSocket.makeCallingRequest(
          requestId,
          url,
          method.name,
          headers?.map { Pair(it.name, it.value) } ?: emptyList(),
          body
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Calling HTTP request failed", t)
        null
      }
      try {
        when (response) {
          is CallingResponse.Success -> manager().receivedHttpResponse(requestId, response.responseStatus, response.responseBody ?: ByteArray(0))
          else -> manager().httpRequestFailed(requestId)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to hand HTTP response to RingRTC", t)
      }
    }
  }

  override fun onGroupCallRingUpdate(groupId: ByteArray, ringId: Long, sender: UUID, update: CallManager.RingUpdate?) {
    Log.i(TAG, "Group ring ignored: $update")
  }

  override fun onNetworkRouteChanged(remote: Remote?, networkRoute: NetworkRoute?) = Unit

  override fun onAudioLevels(remote: Remote?, capturedLevel: Int, receivedLevel: Int) = Unit

  override fun onLowBandwidthForVideo(remote: Remote?, recovered: Boolean) = Unit

  // endregion

  private fun startAudio() {
    val audioManager = AppDeps.context.getSystemService(AudioManager::class.java) ?: return
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      .setAudioAttributes(attributes)
      .build()
    audioFocusRequest = request
    audioManager.requestAudioFocus(request)
  }

  private fun stopAudio() {
    val audioManager = AppDeps.context.getSystemService(AudioManager::class.java) ?: return
    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    audioFocusRequest = null
    audioManager.mode = AudioManager.MODE_NORMAL
  }
}

package dev.sam.wearsignal.net

import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.CdsiProtocolException
import org.signal.libsignal.net.Network
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.cds.CdsiV2Service
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.push.exceptions.CdsiInvalidTokenException
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.push.CdsiAuthResponse
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

/**
 * Contact Discovery Service (CDSI) endpoint: turns phone numbers into Signal account IDs.
 *
 * Trimmed copy of Signal-Android's `org.signal.network.api.CdsApi` (that module isn't vendored):
 * GET /v2/directory/auth over the authenticated websocket for a short-lived credential, then run
 * the SGX-attested lookup via libsignal's [Network.cdsiLookup] (wrapped by [CdsiV2Service]).
 *
 * CDSI is rate-limited server-side to deter contact scraping, so callers should look up rarely and
 * cache results (see [dev.sam.wearsignal.contacts.ContactDirectory]).
 */
class CdsApi(private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket) {

  companion object {
    private val TAG = Log.tag(CdsApi::class)
  }

  fun getRegisteredUsers(
    previousE164s: Set<String>,
    newE164s: Set<String>,
    serviceIds: Map<ServiceId, ProfileKey>,
    token: Optional<ByteArray>,
    timeoutMs: Long?,
    libsignalNetwork: Network,
    tokenSaver: Consumer<ByteArray>
  ): NetworkResult<CdsiV2Service.Response> {
    val authRequest = WebSocketRequestMessage.get("/v2/directory/auth")

    return NetworkResult.fromWebSocketRequest(authWebSocket, authRequest, CdsiAuthResponse::class)
      .then { auth ->
        val service = CdsiV2Service(libsignalNetwork)
        val request = CdsiV2Service.Request(previousE164s, newE164s, serviceIds, token)
        val single = service.getRegisteredUsers(auth.username, auth.password, request, tokenSaver)

        try {
          if (timeoutMs == null) {
            single.blockingGet()
          } else {
            single.timeout(timeoutMs, TimeUnit.MILLISECONDS).blockingGet()
          }
        } catch (e: RuntimeException) {
          when (val cause = e.cause) {
            is InterruptedException -> NetworkResult.NetworkError(IOException("Interrupted", cause))
            is TimeoutException -> NetworkResult.NetworkError(IOException("Timed out"))
            is CdsiProtocolException -> NetworkResult.NetworkError(IOException("CdsiProtocol", cause))
            is CdsiInvalidTokenException -> NetworkResult.NetworkError(IOException("CdsiInvalidToken", cause))
            else -> {
              Log.w(TAG, "Unexpected exception", cause)
              NetworkResult.NetworkError(IOException(cause))
            }
          }
        }
      }
  }
}

package dev.sam.wearsignal.calls

import dev.sam.wearsignal.AppDeps
import org.signal.network.NetworkResult
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.messages.calls.TurnServerInfo
import org.whispersystems.signalservice.internal.push.GetCallingRelaysResponse

/**
 * The calling REST surface the vendored lib models but never wired up:
 * TURN/STUN relay servers for ICE, fetched over the authenticated websocket.
 */
object CallingApi {

  /** GET /v2/calling/relays — requires a connected authenticated websocket. */
  fun turnServers(): List<TurnServerInfo> {
    val request = WebSocketRequestMessage.get("/v2/calling/relays")
    val result = NetworkResult.fromWebSocket<GetCallingRelaysResponse> { AppDeps.net.authWebSocket.request(request) }
    return when (result) {
      is NetworkResult.Success -> result.result.relays.orEmpty()
      else -> throw java.io.IOException("Relay fetch failed: $result")
    }
  }
}

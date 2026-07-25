package dev.sam.wearsignal.calls

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Holds the authenticated websocket open for the duration of a call session and keeps
 * draining it, so answers/ICE/hangups reach the engine within seconds instead of at the
 * next poll. Regular messages arriving mid-call are stored like any drain.
 */
object CallSignaling {

  private val TAG = Log.tag(CallSignaling::class)
  private const val READ_TIMEOUT_MS = 2_000L
  private const val BATCH_SIZE = 16
  private const val RECONNECT_BACKOFF_MS = 1_000L

  /**
   * Session token: each start() bumps it and binds a fresh worker to the new value; a
   * worker exits once the generation moves past its own (stop() or a newer start()).
   * A bare boolean can't distinguish "stopped" from "stopped and immediately
   * restarted", which previously let an old worker disconnect the new session's socket.
   */
  @Volatile
  private var generation = 0

  @Synchronized
  fun start() {
    generation += 1
    val myGeneration = generation
    thread(name = "call-signaling-$myGeneration") { loop(myGeneration) }
  }

  @Synchronized
  fun stop() {
    generation += 1
  }

  private fun loop(myGeneration: Int) {
    val webSocket = AppDeps.net.authWebSocket
    val processor = AppDeps.envelopeProcessor
    try {
      webSocket.connect()
      while (generation == myGeneration) {
        try {
          webSocket.readMessageBatch(READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            for (response in batch) {
              val message = processor.process(response.envelope, response.serverDeliveredTimestamp)
              if (message != null) {
                processor.store(message)
              }
              webSocket.sendAck(response)
            }
          }
        } catch (e: TimeoutException) {
          // Quiet socket. Reconnect if the connection dropped; otherwise keep listening.
          if (generation == myGeneration && webSocket.stateSnapshot != WebSocketConnectionState.CONNECTED) {
            Log.w(TAG, "Signaling socket dropped (${webSocket.stateSnapshot}); reconnecting")
            webSocket.connect()
          }
        } catch (t: Throwable) {
          // A network blip must not kill signaling mid-call: back off and reconnect.
          if (generation != myGeneration) break
          Log.w(TAG, "Signaling read failed; reconnecting", t)
          Thread.sleep(RECONNECT_BACKOFF_MS)
          try {
            webSocket.connect()
          } catch (connectError: Throwable) {
            Log.w(TAG, "Signaling reconnect failed; retrying", connectError)
          }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Signaling loop died", t)
    } finally {
      // Only the last worker standing releases the socket, and only when no session
      // (which may have restarted us) still needs it.
      if (!CallEngine.isSessionActive) {
        try {
          webSocket.disconnect()
        } catch (_: Throwable) {
        }
      }
    }
  }
}

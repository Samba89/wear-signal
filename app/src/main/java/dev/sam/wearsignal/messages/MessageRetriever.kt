package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Connects the authenticated websocket, drains the message queue, acks each envelope,
 * and disconnects. Returns the new messages that should appear in the UI/notifications.
 */
class MessageRetriever(private val processor: EnvelopeProcessor) {

  companion object {
    private val TAG = Log.tag(MessageRetriever::class)
    private const val KEEP_ALIVE_TOKEN = "drain"
    private const val READ_TIMEOUT_MS = 10_000L
    private const val BATCH_SIZE = 64

    /** How long a slow network may take to establish the connection before the poll fails. */
    private const val CONNECT_DEADLINE_MS = 45_000L

    /** Upper bound on one drain; the server's queue-empty signal normally ends it much sooner. */
    private const val DRAIN_DEADLINE_MS = 5L * 60 * 1000
  }

  data class Drained(
    val messages: List<EnvelopeProcessor.IncomingMessage>,
    /** True when the drain stopped at [DRAIN_DEADLINE_MS] with the queue possibly not empty. */
    val incomplete: Boolean
  )

  /**
   * [onProgress] is called after each batch with the running count of envelopes received
   * (messages, receipts, and other traffic) — the server doesn't reveal the queue size.
   */
  fun drainQueue(onProgress: (envelopes: Int) -> Unit = {}): Drained {
    val webSocket = AppDeps.net.authWebSocket
    val collected = mutableListOf<EnvelopeProcessor.IncomingMessage>()
    var envelopes = 0
    var incomplete = false
    val start = System.currentTimeMillis()

    try {
      // The keep-alive token suppresses the socket's 30s idle disconnect, which otherwise
      // cuts off any drain that runs longer than that (acks don't count as activity).
      webSocket.registerKeepAliveToken(KEEP_ALIVE_TOKEN)

      var hasMore = true
      var batches = 0
      while (hasMore) {
        if (System.currentTimeMillis() - start > DRAIN_DEADLINE_MS) {
          Log.w(TAG, "Drain deadline reached after $envelopes envelope(s); leaving the rest for the next poll")
          incomplete = true
          break
        }

        hasMore = try {
          webSocket.readMessageBatch(READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            for (response in batch) {
              val message = processor.process(response.envelope, response.serverDeliveredTimestamp)
              if (message != null && processor.store(message)) {
                collected += message
              }
              webSocket.sendAck(response)
            }
            envelopes += batch.size
            onProgress(envelopes)
          }.also { batches++ }
        } catch (e: TimeoutException) {
          when (webSocket.stateSnapshot) {
            // Connected but silent: the server has nothing more for us.
            WebSocketConnectionState.CONNECTED -> {
              Log.i(TAG, "Queue read timed out while connected; assuming drained")
              false
            }
            // Still establishing on a slow network: keep waiting, up to a deadline.
            WebSocketConnectionState.CONNECTING -> {
              if (envelopes == 0 && System.currentTimeMillis() - start < CONNECT_DEADLINE_MS) {
                Log.i(TAG, "Still connecting; waiting")
                true
              } else {
                throw IOException("Websocket stuck connecting")
              }
            }
            else -> throw IOException("Websocket not connected (${webSocket.stateSnapshot})")
          }
        }
      }
      Log.i(TAG, "Drained queue: $envelopes envelope(s), ${collected.size} new message(s) over $batches batch(es) in ${System.currentTimeMillis() - start}ms")
    } finally {
      webSocket.removeKeepAliveToken(KEEP_ALIVE_TOKEN)
      webSocket.disconnect()
    }

    AppDeps.account.lastPollAt = System.currentTimeMillis()
    return Drained(collected, incomplete)
  }
}

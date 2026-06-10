package dev.sam.wearsignal.messages

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log

/**
 * Connects the authenticated websocket, drains the message queue, acks each envelope,
 * and disconnects. Returns the new messages that should appear in the UI/notifications.
 */
class MessageRetriever(private val processor: EnvelopeProcessor) {

  companion object {
    private val TAG = Log.tag(MessageRetriever::class)
    private const val READ_TIMEOUT_MS = 10_000L
    private const val BATCH_SIZE = 64
  }

  fun drainQueue(): List<EnvelopeProcessor.IncomingMessage> {
    val webSocket = AppDeps.net.authWebSocket
    val collected = mutableListOf<EnvelopeProcessor.IncomingMessage>()

    try {
      webSocket.connect()

      var attempts = 0
      var hasMore = true
      while (hasMore && attempts < 100) {
        attempts++
        hasMore = try {
          webSocket.readMessageBatch(READ_TIMEOUT_MS, BATCH_SIZE) { batch ->
            for (response in batch) {
              val message = processor.process(response.envelope, response.serverDeliveredTimestamp)
              if (message != null) {
                processor.store(message)
                collected += message
              }
              webSocket.sendAck(response)
            }
          }
        } catch (e: java.util.concurrent.TimeoutException) {
          Log.i(TAG, "Queue read timed out; assuming drained")
          false
        }
      }
      Log.i(TAG, "Drained queue: ${collected.size} new message(s) over $attempts batch(es)")
    } finally {
      webSocket.disconnect()
    }

    AppDeps.account.lastPollAt = System.currentTimeMillis()
    return collected
  }
}

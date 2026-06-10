package dev.sam.wearsignal.poll

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log

/**
 * One poll cycle: drain the queue, then notify for new incoming messages
 * unless suppressed (phone connected, or an explicitly silent drain).
 */
object Poller {

  private val TAG = Log.tag(Poller::class)

  /** Runs a drain. Returns the number of new messages. Safe to call from any background thread. */
  fun poll(silent: Boolean = false): Int {
    if (!AppDeps.account.isLinked) {
      Log.w(TAG, "Not linked; skipping poll")
      return 0
    }

    val newMessages = AppDeps.retriever.drainQueue()

    if (!silent) {
      AppDeps.notifier.notify(newMessages) { aci -> resolveName(aci) }
    }

    return newMessages.size
  }

  fun resolveName(aci: String): String {
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT name FROM contacts WHERE aci = ?",
      arrayOf(aci)
    ).use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) {
        return cursor.getString(0)
      }
    }
    return aci.take(8)
  }
}

package dev.sam.wearsignal.poll

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.ProfileNameResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.signal.core.util.logging.Log

/**
 * One poll cycle: drain the queue, then notify for new incoming messages
 * unless suppressed (phone connected, or an explicitly silent drain).
 */
object Poller {

  private val TAG = Log.tag(Poller::class)

  sealed interface Result {
    data class Success(val newMessages: Int) : Result
    data class Failure(val message: String) : Result
  }

  private val _progress = MutableStateFlow<String?>(null)

  /** Short human-readable stage of the running poll ("Received 37", "Images 2 of 5"), or null when idle. */
  val progress: StateFlow<String?> = _progress.asStateFlow()

  private val lock = Any()

  /**
   * Runs a drain. Never throws. Safe to call from any background thread; concurrent calls
   * (e.g. a manual check while the background poll runs) queue up rather than sharing the socket.
   */
  fun poll(silent: Boolean = false): Result = synchronized(lock) {
    try {
      pollLocked(silent)
    } finally {
      _progress.value = null
    }
  }

  private fun pollLocked(silent: Boolean): Result {
    if (!AppDeps.account.isLinked) {
      Log.w(TAG, "Not linked; skipping poll")
      return Result.Failure("Not linked")
    }

    _progress.value = "Connecting…"
    var received = 0
    val drained = try {
      AppDeps.retriever.drainQueue { envelopes ->
        received = envelopes
        _progress.value = "Received $envelopes"
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Poll failed to drain the queue after $received envelope(s)", t)
      try {
        AppDeps.net.authWebSocket.disconnect()
      } catch (_: Throwable) {
      }
      // Anything received before the drop is already stored; the rest stays queued.
      return Result.Failure(if (received > 0) "Connection lost after $received — check again" else "Couldn't connect")
    }
    val newMessages = drained.messages

    // Resolve names, group state, and photos for new senders, plus any contacts/groups
    // still awaiting an avatar backfill (cheap no-op once everything is fetched).
    val pendingAcis = newMessages.filterNot { it.fromSelf }.map { it.senderAci } + ProfileNameResolver.pendingAvatarAcis()
    val pendingGroups = newMessages.mapNotNull { it.groupId } + GroupStateResolver.pendingAvatarGroupIds()
    if (pendingAcis.isNotEmpty() || pendingGroups.isNotEmpty()) {
      _progress.value = "Updating contacts…"
      ProfileNameResolver.resolvePending(pendingAcis)
      GroupStateResolver.resolvePending(pendingGroups)
      AppDeps.net.authWebSocket.disconnect()
    }

    // Contacts without a Signal profile photo fall back to their synced address-book photo.
    AppDeps.avatars.backfillDeviceContactPhotos()

    // Fetch pending image attachments (bounded per run) and apply retention.
    AppDeps.attachments.downloadPending { done, total ->
      _progress.value = "Images $done of $total"
    }

    if (!silent) {
      AppDeps.notifier.notify(newMessages) { aci -> resolveName(aci) }
    }

    return if (drained.incomplete) {
      Result.Failure("More waiting — check again")
    } else {
      Result.Success(newMessages.size)
    }
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

package dev.sam.wearsignal.poll

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.calls.CallEngine
import dev.sam.wearsignal.calls.CallLog
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.ProfileNameResolver
import dev.sam.wearsignal.tile.Glanceables
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

  /** Runs a drain. Never throws. Safe to call from any background thread. */
  fun poll(silent: Boolean = false): Result {
    if (!AppDeps.account.isLinked) {
      Log.w(TAG, "Not linked; skipping poll")
      return Result.Failure("Not linked")
    }
    if (CallEngine.isSessionActive) {
      // The call session's signaling loop is draining the queue already.
      return Result.Success(0)
    }

    val newMessages = try {
      AppDeps.retriever.drainQueue()
    } catch (t: Throwable) {
      Log.w(TAG, "Poll failed to drain the queue", t)
      try {
        AppDeps.net.authWebSocket.disconnect()
      } catch (_: Throwable) {
      }
      return Result.Failure("Couldn't connect")
    }

    // Take even on silent drains: they swallow missed-call alerts the way they swallow messages.
    val missedCalls = CallLog.takeUnnotifiedMissed()

    // Resolve names, group state, and photos for new senders and callers, plus any
    // contacts/groups still awaiting an avatar backfill (cheap no-op once fetched).
    val pendingAcis = newMessages.filterNot { it.fromSelf }.map { it.senderAci } +
      missedCalls.filterNot { it.isGroup }.map { it.peer } +
      ProfileNameResolver.pendingAvatarAcis()
    val pendingGroups = newMessages.mapNotNull { it.groupId } + GroupStateResolver.pendingAvatarGroupIds()
    if (pendingAcis.isNotEmpty() || pendingGroups.isNotEmpty()) {
      ProfileNameResolver.resolvePending(pendingAcis)
      GroupStateResolver.resolvePending(pendingGroups)
      // A fresh offer drained above may have started a call session that owns the socket.
      if (!CallEngine.isSessionActive) {
        AppDeps.net.authWebSocket.disconnect()
      }
    }

    // Contacts without a Signal profile photo fall back to their synced address-book photo.
    AppDeps.avatars.backfillDeviceContactPhotos()

    // Fetch pending image attachments (bounded per run) and apply retention.
    AppDeps.attachments.downloadPending()

    if (!silent) {
      AppDeps.notifier.notify(newMessages) { aci -> resolveName(aci) }
      AppDeps.notifier.notifyMissedCalls(missedCalls) { aci -> resolveName(aci) }
    }

    Glanceables.requestUpdate(AppDeps.context)

    return Result.Success(newMessages.size)
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

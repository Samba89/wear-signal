package dev.sam.wearsignal.poll

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.messages.GroupStateResolver
import dev.sam.wearsignal.messages.ProfileNameResolver
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

    // Resolve names, group state, and photos for new senders, plus any contacts/groups
    // still awaiting an avatar backfill (cheap no-op once everything is fetched).
    val pendingAcis = newMessages.filterNot { it.fromSelf }.map { it.senderAci } + ProfileNameResolver.pendingAvatarAcis()
    val pendingGroups = newMessages.mapNotNull { it.groupId } + GroupStateResolver.pendingAvatarGroupIds()
    if (pendingAcis.isNotEmpty() || pendingGroups.isNotEmpty()) {
      ProfileNameResolver.resolvePending(pendingAcis)
      GroupStateResolver.resolvePending(pendingGroups)
      AppDeps.net.authWebSocket.disconnect()
    }

    // Contacts without a Signal profile photo fall back to their synced address-book photo.
    AppDeps.avatars.backfillDeviceContactPhotos()

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

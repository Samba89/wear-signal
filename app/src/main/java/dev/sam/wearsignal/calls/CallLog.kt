package dev.sam.wearsignal.calls

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.internal.push.CallMessage
import org.whispersystems.signalservice.internal.push.SyncMessage

/**
 * Call history derived from drained signaling: 1:1 CallMessages seen directly, plus the
 * phone's CallEvent sync messages (authoritative — the phone knows how a call ended, and
 * is the only source for group call history since group ringing is opaque to us).
 * By the time a poll drains an offer the ring window has long passed, so offers land as
 * missed calls unless a later hangup or sync event upgrades them.
 */
object CallLog {

  private val TAG = Log.tag(CallLog::class)

  const val OUTCOME_MISSED = "missed"
  const val OUTCOME_ANSWERED = "answered"
  const val OUTCOME_DECLINED = "declined"
  const val OUTCOME_OBSERVED = "observed"

  private const val MAX_PER_PEER = 25

  data class Entry(
    val callId: Long,
    val peer: String,
    val isGroup: Boolean,
    val isVideo: Boolean,
    val outgoing: Boolean,
    val outcome: String,
    val startedAt: Long
  )

  fun handleCallMessage(senderAci: String, sentAt: Long, call: CallMessage) {
    val offer = call.offer
    val offerId = offer?.id
    if (offer != null && offerId != null) {
      upsert(
        callId = offerId,
        peer = senderAci,
        isGroup = false,
        isVideo = offer.type == CallMessage.Offer.Type.OFFER_VIDEO_CALL,
        outgoing = false,
        outcome = OUTCOME_MISSED,
        startedAt = sentAt,
        notified = false,
        authoritative = false
      )
      Log.i(TAG, "Recorded drained call offer (${offer.type})")
      return
    }

    val hangup = call.hangup
    val hangupId = hangup?.id
    if (hangup != null && hangupId != null) {
      // Accepted/declined on another of our devices upgrades the offer's missed row.
      // A normal or busy hangup is the caller giving up: the row stays missed.
      val outcome = when (hangup.type) {
        CallMessage.Hangup.Type.HANGUP_ACCEPTED -> OUTCOME_ANSWERED
        CallMessage.Hangup.Type.HANGUP_DECLINED -> OUTCOME_DECLINED
        else -> return
      }
      AppDeps.database.writableDatabase.execSQL(
        "UPDATE calls SET outcome = ?, notified = 1 WHERE peer = ? AND call_id = ?",
        arrayOf<Any>(outcome, senderAci, hangupId)
      )
    }
    // Answers, ICE updates, busy, and opaque messages only matter to a live call.
  }

  fun handleSyncCallEvent(event: SyncMessage.CallEvent) {
    val callId = event.callId ?: return
    val conversation = event.conversationId?.toByteArray() ?: return
    val type = event.type ?: return
    if (type == SyncMessage.CallEvent.Type.AD_HOC_CALL) return // call links: not supported

    val isGroup = type == SyncMessage.CallEvent.Type.GROUP_CALL
    val peer = if (isGroup) {
      Base64.encodeWithPadding(conversation)
    } else {
      ServiceId.parseOrNull(conversation)?.toString() ?: return
    }

    val db = AppDeps.database.writableDatabase
    val outcome = when (event.event) {
      SyncMessage.CallEvent.Event.ACCEPTED -> OUTCOME_ANSWERED
      SyncMessage.CallEvent.Event.NOT_ACCEPTED -> OUTCOME_MISSED
      SyncMessage.CallEvent.Event.OBSERVED -> OUTCOME_OBSERVED
      SyncMessage.CallEvent.Event.DELETE -> {
        db.delete("calls", "peer = ? AND call_id = ?", arrayOf(peer, callId.toString()))
        return
      }
      else -> return
    }

    // The phone already dealt with this call (rang or placed it), so never notify for it.
    upsert(
      callId = callId,
      peer = peer,
      isGroup = isGroup,
      isVideo = type == SyncMessage.CallEvent.Type.VIDEO_CALL,
      outgoing = event.direction == SyncMessage.CallEvent.Direction.OUTGOING,
      outcome = outcome,
      startedAt = event.timestamp ?: System.currentTimeMillis(),
      notified = true,
      authoritative = true
    )
  }

  /** Outcome of a call the watch itself rang or placed. Never notifies (the user was there). */
  fun recordLiveCall(callId: Long, peer: String, isVideo: Boolean, outgoing: Boolean, outcome: String, startedAt: Long) {
    upsert(
      callId = callId,
      peer = peer,
      isGroup = false,
      isVideo = isVideo,
      outgoing = outgoing,
      outcome = outcome,
      startedAt = startedAt,
      notified = true,
      authoritative = true
    )
  }

  /** Latest group-call era seen for a group; Phase 2's "call may be ongoing" signal. */
  fun recordGroupCallUpdate(groupId: String, eraId: String, at: Long) {
    AppDeps.database.writableDatabase.execSQL(
      "UPDATE groups SET active_era = ?, active_era_at = ? WHERE group_id = ? AND active_era_at < ?",
      arrayOf<Any>(eraId, at, groupId, at)
    )
  }

  /**
   * Incoming missed calls that were never surfaced, oldest first; marks exactly those
   * rows notified (in one transaction, so a concurrent insert can't be swallowed
   * unreturned) — a silent drain then discards them the way it discards messages.
   */
  fun takeUnnotifiedMissed(): List<Entry> {
    val db = AppDeps.database.writableDatabase
    db.beginTransaction()
    try {
      val result = query("outcome = ? AND outgoing = 0 AND notified = 0", arrayOf(OUTCOME_MISSED), "started_at ASC")
      db.execSQL("UPDATE calls SET notified = 1 WHERE outcome = ? AND outgoing = 0 AND notified = 0", arrayOf(OUTCOME_MISSED))
      db.setTransactionSuccessful()
      return result
    } finally {
      db.endTransaction()
    }
  }

  /** Calls of one conversation, oldest first, for merging into the thread view. */
  fun callsFor(peer: String): List<Entry> = query("peer = ?", arrayOf(peer), "started_at ASC")

  /** The most recent call of every conversation, for the conversation list. */
  fun lastCallPerPeer(): List<Entry> {
    val result = mutableListOf<Entry>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT call_id, peer, is_group, is_video, outgoing, outcome, MAX(started_at) FROM calls GROUP BY peer",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) result += entryOf(cursor)
    }
    return result
  }

  private fun query(where: String, args: Array<String>, orderBy: String): List<Entry> {
    val result = mutableListOf<Entry>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT call_id, peer, is_group, is_video, outgoing, outcome, started_at FROM calls WHERE $where ORDER BY $orderBy",
      args
    ).use { cursor ->
      while (cursor.moveToNext()) result += entryOf(cursor)
    }
    return result
  }

  private fun entryOf(cursor: android.database.Cursor) = Entry(
    callId = cursor.getLong(0),
    peer = cursor.getString(1),
    isGroup = cursor.getInt(2) == 1,
    isVideo = cursor.getInt(3) == 1,
    outgoing = cursor.getInt(4) == 1,
    outcome = cursor.getString(5),
    startedAt = cursor.getLong(6)
  )

  private fun upsert(
    callId: Long,
    peer: String,
    isGroup: Boolean,
    isVideo: Boolean,
    outgoing: Boolean,
    outcome: String,
    startedAt: Long,
    notified: Boolean,
    authoritative: Boolean
  ) {
    val db = AppDeps.database.writableDatabase
    val values = ContentValues().apply {
      put("call_id", callId)
      put("peer", peer)
      put("is_group", if (isGroup) 1 else 0)
      put("is_video", if (isVideo) 1 else 0)
      put("outgoing", if (outgoing) 1 else 0)
      put("outcome", outcome)
      put("started_at", startedAt)
      put("notified", if (notified) 1 else 0)
    }
    val inserted = db.insertWithOnConflict("calls", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    if (inserted == -1L) {
      if (authoritative) {
        // Sync events know the real outcome and direction; keep the earliest timestamp
        // (the drained offer's send time) and never un-notify.
        db.execSQL(
          "UPDATE calls SET outcome = ?, outgoing = ?, is_video = ?, notified = MAX(notified, ?) WHERE peer = ? AND call_id = ?",
          arrayOf<Any>(outcome, if (outgoing) 1 else 0, if (isVideo) 1 else 0, if (notified) 1 else 0, peer, callId)
        )
      }
    } else {
      db.execSQL(
        """
        DELETE FROM calls WHERE peer = ? AND _id NOT IN (
          SELECT _id FROM calls WHERE peer = ? ORDER BY started_at DESC LIMIT $MAX_PER_PEER
        )
        """,
        arrayOf(peer, peer)
      )
    }
  }
}

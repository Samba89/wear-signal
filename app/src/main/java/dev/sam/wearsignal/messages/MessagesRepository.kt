package dev.sam.wearsignal.messages

import android.content.ContentValues
import dev.sam.wearsignal.db.WatchDatabase

/** One conversation in the conversation list, keyed by [peer] (group id or 1:1 ACI). */
data class ConversationRow(
  val peer: String,
  val title: String,
  val isGroup: Boolean,
  val lastBody: String,
  val lastAt: Long,
  val lastFromSelf: Boolean,
  val lastSender: String
)

/** One message inside a conversation thread. */
data class MessageRow(
  val sender: String,
  val senderAci: String,
  val body: String,
  val sentAt: Long,
  val fromSelf: Boolean,
  val delivered: Boolean = false,
  val read: Boolean = false,
  /** Content type of the attachment, when the message has one. */
  val attachmentType: String? = null,
  /** Local file of the downloaded (downscaled) image, when available. */
  val attachmentPath: String? = null,
  /** Emoji reactions to this message, grouped per emoji, most-used first. */
  val reactions: List<MessageReaction> = emptyList()
) {
  /** The emoji we reacted with, if any (one reaction per person, like Signal). */
  val myReaction: String? get() = reactions.firstOrNull { it.mine }?.emoji
}

/** One emoji's reactions to a message: how many people, and whether we're one of them. */
data class MessageReaction(val emoji: String, val count: Int, val mine: Boolean)

/** Placeholder body text for attachment messages ("📷 Photo" / "📎 Attachment"). */
fun attachmentPlaceholder(contentType: String?): String =
  if (contentType?.startsWith("image/") == true) "📷 Photo" else "📎 Attachment"

/**
 * Stores and reads decrypted messages, grouped into conversations by peer
 * (group id for groups, the other party's ACI for 1:1). Each conversation is
 * pruned to the most recent [MAX_PER_CONVERSATION] messages.
 */
class MessagesRepository(private val db: WatchDatabase) {

  companion object {
    const val MAX_PER_CONVERSATION = 100
  }

  fun insert(
    peer: String,
    senderAci: String,
    groupId: String?,
    body: String,
    sentAt: Long,
    serverAt: Long,
    fromSelf: Boolean,
    attachmentType: String? = null,
    attachmentPointer: ByteArray? = null
  ) {
    val values = ContentValues().apply {
      put("peer", peer)
      put("sender_aci", senderAci)
      put("group_id", groupId)
      put("body", body)
      put("sent_at", sentAt)
      put("server_at", serverAt)
      put("from_self", if (fromSelf) 1 else 0)
      put("attachment_type", attachmentType)
      put("attachment_pointer", attachmentPointer)
    }
    db.writableDatabase.insert("messages", null, values)
    db.writableDatabase.execSQL(
      """
      DELETE FROM messages WHERE peer = ? AND _id NOT IN (
        SELECT _id FROM messages WHERE peer = ? ORDER BY sent_at DESC LIMIT $MAX_PER_CONVERSATION
      )
      """,
      arrayOf(peer, peer)
    )
    pruneOrphanedReactions(peer)
  }

  /** Drops reactions whose target message no longer exists (pruned past the cap or merged away). */
  private fun pruneOrphanedReactions(peer: String) {
    db.writableDatabase.execSQL(
      """
      DELETE FROM reactions WHERE peer = ? AND NOT EXISTS (
        SELECT 1 FROM messages m
        WHERE m.peer = reactions.peer AND m.sent_at = reactions.target_sent_at AND m.sender_aci = reactions.target_author_aci
      )
      """,
      arrayOf(peer)
    )
  }

  /**
   * Applies one person's reaction to a message. A new reaction replaces their previous one;
   * [remove] retracts it. Returns false for reactions to messages we don't have (dropped).
   */
  fun applyReaction(
    peer: String,
    targetSentAt: Long,
    targetAuthorAci: String,
    reacterAci: String,
    emoji: String,
    remove: Boolean
  ): Boolean {
    if (remove) {
      return db.writableDatabase.delete(
        "reactions",
        "target_sent_at = ? AND target_author_aci = ? AND reacter_aci = ?",
        arrayOf(targetSentAt.toString(), targetAuthorAci, reacterAci)
      ) > 0
    }
    val targetExists = db.readableDatabase.rawQuery(
      "SELECT 1 FROM messages WHERE peer = ? AND sent_at = ? AND sender_aci = ? LIMIT 1",
      arrayOf(peer, targetSentAt.toString(), targetAuthorAci)
    ).use { it.moveToFirst() }
    if (!targetExists) return false

    val values = ContentValues().apply {
      put("peer", peer)
      put("target_sent_at", targetSentAt)
      put("target_author_aci", targetAuthorAci)
      put("reacter_aci", reacterAci)
      put("emoji", emoji)
      put("at", System.currentTimeMillis())
    }
    db.writableDatabase.insertWithOnConflict("reactions", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    return true
  }

  /**
   * The [limit] most recently active conversations, with group titles / contact names
   * resolved where known. Ask for one more than you show to learn whether more exist.
   */
  fun conversations(limit: Int = Int.MAX_VALUE): List<ConversationRow> {
    val result = mutableListOf<ConversationRow>()
    db.readableDatabase.rawQuery(
      """
      SELECT m.peer, m.group_id IS NOT NULL, m.body, MAX(m.sent_at) AS last_at, m.from_self,
             g.title, c.name, sc.name, m.sender_aci, m.attachment_type
      FROM messages m
      LEFT JOIN groups g ON g.group_id = m.peer
      LEFT JOIN contacts c ON c.aci = m.peer
      LEFT JOIN contacts sc ON sc.aci = m.sender_aci
      GROUP BY m.peer
      ORDER BY last_at DESC
      LIMIT ?
      """,
      arrayOf(limit.toString())
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val peer = cursor.getString(0)
        val isGroup = cursor.getInt(1) == 1
        val fromSelf = cursor.getInt(4) == 1
        val groupTitle = if (cursor.isNull(5)) null else cursor.getString(5)
        val contactName = if (cursor.isNull(6)) null else cursor.getString(6)
        val senderName = if (cursor.isNull(7)) null else cursor.getString(7)
        val senderAci = cursor.getString(8)
        val attachmentType = if (cursor.isNull(9)) null else cursor.getString(9)
        val body = cursor.getString(2)
        result += ConversationRow(
          peer = peer,
          title = when {
            isGroup -> groupTitle ?: "Group"
            else -> contactName ?: peer.take(8)
          },
          isGroup = isGroup,
          lastBody = body.ifEmpty { if (attachmentType != null) attachmentPlaceholder(attachmentType) else body },
          lastAt = cursor.getLong(3),
          lastFromSelf = fromSelf,
          lastSender = if (fromSelf) "Me" else senderName ?: senderAci.take(8)
        )
      }
    }
    return result
  }

  /**
   * Incoming messages not yet seen anywhere — shown on the tile and complication.
   * "Seen" means read on the phone (synced over) or its thread viewed on the watch.
   */
  fun unreadCount(): Int {
    db.readableDatabase.rawQuery(
      "SELECT COUNT(*) FROM messages WHERE from_self = 0 AND seen_at = 0",
      null
    ).use { cursor ->
      return if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
  }

  /** Marks a conversation's incoming messages seen (its thread is on screen). Returns how many changed. */
  fun markThreadSeen(peer: String): Int {
    val values = ContentValues().apply { put("seen_at", System.currentTimeMillis()) }
    return db.writableDatabase.update("messages", values, "peer = ? AND from_self = 0 AND seen_at = 0", arrayOf(peer))
  }

  /**
   * Folds a PNI-keyed 1:1 conversation into the person's ACI thread, once the association is
   * learned (their PNI signature, or CDSI returning the ACI). Also repoints the contact
   * directory's send target so future sends go to the ACI. Returns whether anything changed.
   */
  fun mergePniIntoAci(pni: String, aci: String): Boolean {
    if (pni == aci) return false
    val writable = db.writableDatabase
    // Carry the address-book name over to the contacts table (keyed by ACI) so the merged
    // thread is titled, without clobbering a name already resolved from their Signal profile.
    writable.execSQL(
      "INSERT INTO contacts (aci, name, fetched_at) SELECT ?, name, 0 FROM directory WHERE aci = ? AND name IS NOT NULL " +
        "ON CONFLICT(aci) DO UPDATE SET name = COALESCE(contacts.name, excluded.name)",
      arrayOf(aci, pni)
    )
    val directoryMoved = writable.compileStatement("UPDATE directory SET aci = ? WHERE aci = ?").use {
      it.bindString(1, aci)
      it.bindString(2, pni)
      it.executeUpdateDelete()
    }
    val moved = writable.compileStatement("UPDATE messages SET peer = ? WHERE peer = ?").use {
      it.bindString(1, aci)
      it.bindString(2, pni)
      it.executeUpdateDelete()
    }
    writable.execSQL("UPDATE reactions SET peer = ? WHERE peer = ?", arrayOf(aci, pni))
    if (moved > 0) {
      writable.execSQL(
        """
        DELETE FROM messages WHERE peer = ? AND _id NOT IN (
          SELECT _id FROM messages WHERE peer = ? ORDER BY sent_at DESC LIMIT $MAX_PER_CONVERSATION
        )
        """,
        arrayOf(aci, aci)
      )
      pruneOrphanedReactions(aci)
    }
    return moved > 0 || directoryMoved > 0
  }

  /**
   * Messages of one conversation, oldest first, with sender names resolved from the contacts
   * cache and reactions attached. [selfAci] identifies our own reactions (for the toggle UI).
   */
  fun thread(peer: String, selfAci: String? = null): List<MessageRow> {
    val reactionsByTarget = threadReactions(peer, selfAci)
    val result = mutableListOf<MessageRow>()
    db.readableDatabase.rawQuery(
      """
      SELECT m.sender_aci, m.body, m.sent_at, m.from_self, c.name, m.delivered_at, m.read_at,
             m.attachment_type, m.attachment_path
      FROM messages m LEFT JOIN contacts c ON c.aci = m.sender_aci
      WHERE m.peer = ?
      ORDER BY m.sent_at ASC
      """,
      arrayOf(peer)
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val senderAci = cursor.getString(0)
        val fromSelf = cursor.getInt(3) == 1
        val name = if (cursor.isNull(4)) null else cursor.getString(4)
        val sentAt = cursor.getLong(2)
        result += MessageRow(
          sender = when {
            fromSelf -> "Me"
            name != null -> name
            else -> senderAci.take(8)
          },
          senderAci = senderAci,
          body = cursor.getString(1),
          sentAt = sentAt,
          fromSelf = fromSelf,
          delivered = cursor.getLong(5) > 0,
          read = cursor.getLong(6) > 0,
          attachmentType = if (cursor.isNull(7)) null else cursor.getString(7),
          attachmentPath = if (cursor.isNull(8)) null else cursor.getString(8),
          reactions = reactionsByTarget[sentAt to senderAci] ?: emptyList()
        )
      }
    }
    return result
  }

  /** A conversation's reactions grouped per target message and emoji, most-used emoji first. */
  private fun threadReactions(peer: String, selfAci: String?): Map<Pair<Long, String>, List<MessageReaction>> {
    data class Raw(val reacter: String, val emoji: String)
    val rawByTarget = mutableMapOf<Pair<Long, String>, MutableList<Raw>>()
    db.readableDatabase.rawQuery(
      "SELECT target_sent_at, target_author_aci, reacter_aci, emoji FROM reactions WHERE peer = ? ORDER BY at ASC",
      arrayOf(peer)
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val key = cursor.getLong(0) to cursor.getString(1)
        rawByTarget.getOrPut(key) { mutableListOf() } += Raw(cursor.getString(2), cursor.getString(3))
      }
    }
    return rawByTarget.mapValues { (_, raws) ->
      raws.groupBy { it.emoji }
        .map { (emoji, group) -> MessageReaction(emoji, group.size, group.any { it.reacter == selfAci }) }
        .sortedByDescending { it.count }
    }
  }
}

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
  val attachmentPath: String? = null
)

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

  /** Messages of one conversation, oldest first, with sender names resolved from the contacts cache. */
  fun thread(peer: String): List<MessageRow> {
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
        result += MessageRow(
          sender = when {
            fromSelf -> "Me"
            name != null -> name
            else -> senderAci.take(8)
          },
          senderAci = senderAci,
          body = cursor.getString(1),
          sentAt = cursor.getLong(2),
          fromSelf = fromSelf,
          delivered = cursor.getLong(5) > 0,
          read = cursor.getLong(6) > 0,
          attachmentType = if (cursor.isNull(7)) null else cursor.getString(7),
          attachmentPath = if (cursor.isNull(8)) null else cursor.getString(8)
        )
      }
    }
    return result
  }
}

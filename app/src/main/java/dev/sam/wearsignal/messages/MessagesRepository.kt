package dev.sam.wearsignal.messages

import android.content.ContentValues
import dev.sam.wearsignal.db.WatchDatabase
import dev.sam.wearsignal.ui.MessageRow

/**
 * Stores and reads decrypted messages, pruned to the most recent [MAX_MESSAGES].
 */
class MessagesRepository(private val db: WatchDatabase) {

  companion object {
    const val MAX_MESSAGES = 50
  }

  fun insert(senderAci: String, groupId: String?, body: String, sentAt: Long, serverAt: Long, fromSelf: Boolean) {
    val values = ContentValues().apply {
      put("sender_aci", senderAci)
      put("group_id", groupId)
      put("body", body)
      put("sent_at", sentAt)
      put("server_at", serverAt)
      put("from_self", if (fromSelf) 1 else 0)
    }
    db.writableDatabase.insert("messages", null, values)
    db.writableDatabase.execSQL(
      "DELETE FROM messages WHERE _id NOT IN (SELECT _id FROM messages ORDER BY sent_at DESC LIMIT $MAX_MESSAGES)"
    )
  }

  /** Recent messages, newest first, with sender names resolved from the contacts cache where known. */
  fun recent(): List<MessageRow> {
    val result = mutableListOf<MessageRow>()
    db.readableDatabase.rawQuery(
      """
      SELECT m.sender_aci, m.group_id, m.body, m.sent_at, m.from_self, c.name
      FROM messages m LEFT JOIN contacts c ON c.aci = m.sender_aci
      ORDER BY m.sent_at DESC LIMIT $MAX_MESSAGES
      """,
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val senderAci = cursor.getString(0)
        val isGroup = !cursor.isNull(1)
        val fromSelf = cursor.getInt(4) == 1
        val name = if (cursor.isNull(5)) null else cursor.getString(5)
        val sender = when {
          fromSelf -> "Me"
          name != null -> name
          else -> senderAci.take(8)
        }
        result += MessageRow(
          sender = if (isGroup) "$sender 👥" else sender,
          body = cursor.getString(2),
          sentAt = cursor.getLong(3),
          fromSelf = fromSelf
        )
      }
    }
    return result
  }
}

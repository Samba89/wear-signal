package dev.sam.wearsignal.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil
import java.io.File

/**
 * Image attachments, sized for the watch: originals are downloaded from the CDN,
 * decrypted, downscaled to [TARGET_SIZE_PX], and stored as one JPEG per message
 * (~100KB each — the original is never kept).
 *
 * Retention: files older than [MAX_AGE_MS] are deleted, and the directory is capped
 * at [MAX_TOTAL_BYTES] (oldest deleted first). Downloads are retried on each poll
 * while the message is younger than [MAX_AGE_MS]; after that the pointer is dropped.
 */
class AttachmentStore(context: Context) {

  companion object {
    private val TAG = Log.tag(AttachmentStore::class)
    private const val MAX_DOWNLOAD_BYTES = 25L * 1024 * 1024
    private const val TARGET_SIZE_PX = 480
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_DOWNLOADS_PER_RUN = 10
  }

  private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

  /**
   * Downloads any pending image attachments (bounded per run), then applies retention.
   * Call after a drain while the network is usable. Never throws.
   */
  fun downloadPending() {
    val db = AppDeps.database.writableDatabase
    val cutoff = System.currentTimeMillis() - MAX_AGE_MS

    // Expire pointers we'll never download: too old, or a type we don't render.
    db.execSQL(
      "UPDATE messages SET attachment_pointer = NULL WHERE attachment_pointer IS NOT NULL AND (sent_at < ? OR attachment_type IS NULL OR attachment_type NOT LIKE 'image/%')",
      arrayOf(cutoff)
    )

    val pending = mutableListOf<Pair<Long, ByteArray>>()
    db.rawQuery(
      "SELECT _id, attachment_pointer FROM messages WHERE attachment_pointer IS NOT NULL AND attachment_path IS NULL ORDER BY sent_at DESC LIMIT $MAX_DOWNLOADS_PER_RUN",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        pending += cursor.getLong(0) to cursor.getBlob(1)
      }
    }

    for ((messageId, pointerBytes) in pending) {
      try {
        download(messageId, pointerBytes)
      } catch (t: Throwable) {
        Log.w(TAG, "Attachment download for message $messageId failed; will retry next poll", t)
      }
    }

    cleanup()
  }

  private fun download(messageId: Long, pointerBytes: ByteArray) {
    val pointer = AttachmentPointerUtil.createSignalAttachmentPointer(pointerBytes)
    val digest = pointer.digest.orElse(null)
    if (digest == null) {
      Log.w(TAG, "Attachment for message $messageId has no digest; dropping")
      clearPointer(messageId)
      return
    }

    val encrypted = File.createTempFile("attachment", ".tmp", dir)
    val decrypted = File.createTempFile("attachment", ".tmp", dir)
    try {
      val stream = AppDeps.net.messageReceiver.retrieveAttachment(
        pointer,
        encrypted,
        MAX_DOWNLOAD_BYTES,
        AttachmentCipherInputStream.IntegrityCheck.forEncryptedDigest(digest)
      )
      stream.use { input -> decrypted.outputStream().use { input.copyTo(it) } }

      val bitmap = decodeDownscaled(decrypted)
      if (bitmap == null) {
        Log.w(TAG, "Attachment for message $messageId did not decode as an image; dropping")
        clearPointer(messageId)
        return
      }

      val file = File(dir, "att_$messageId.jpg")
      file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
      bitmap.recycle()

      AppDeps.database.writableDatabase.execSQL(
        "UPDATE messages SET attachment_path = ?, attachment_pointer = NULL WHERE _id = ?",
        arrayOf(file.path, messageId)
      )
      Log.i(TAG, "Downloaded attachment for message $messageId (${file.length() / 1024} KB)")
    } finally {
      encrypted.delete()
      decrypted.delete()
    }
  }

  private fun clearPointer(messageId: Long) {
    AppDeps.database.writableDatabase.execSQL(
      "UPDATE messages SET attachment_pointer = NULL WHERE _id = ?",
      arrayOf(messageId)
    )
  }

  /** Age limit, orphan removal (pruned messages), and the total-size cap, oldest first. */
  private fun cleanup() {
    val db = AppDeps.database.writableDatabase
    val referenced = mutableSetOf<String>()
    db.rawQuery("SELECT attachment_path FROM messages WHERE attachment_path IS NOT NULL", emptyArray()).use { cursor ->
      while (cursor.moveToNext()) referenced += cursor.getString(0)
    }

    val now = System.currentTimeMillis()
    val files = (dir.listFiles() ?: return)
      .filter { it.name.startsWith("att_") }
      .sortedBy { it.lastModified() }
      .toMutableList()

    var total = files.sumOf { it.length() }
    for (file in files.toList()) {
      val expired = now - file.lastModified() > MAX_AGE_MS
      val orphaned = file.path !in referenced
      val overCap = total > MAX_TOTAL_BYTES
      if (expired || orphaned || overCap) {
        total -= file.length()
        file.delete()
        files.remove(file)
        db.execSQL("UPDATE messages SET attachment_path = NULL WHERE attachment_path = ?", arrayOf(file.path))
      }
    }
  }

  private fun decodeDownscaled(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= TARGET_SIZE_PX && bounds.outHeight / (sampleSize * 2) >= TARGET_SIZE_PX) {
      sampleSize *= 2
    }
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
  }
}

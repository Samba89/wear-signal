package dev.sam.wearsignal.contacts

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.whispersystems.signalservice.api.crypto.ProfileCipherInputStream
import java.io.File

/**
 * Local cache of avatars, one downscaled JPEG per key (contact ACI or group id):
 * contact photos are downloaded from the CDN and decrypted with the contact's profile
 * key; group photos are handed in already decrypted by GroupStateResolver.
 */
class AvatarStore(context: Context) {

  companion object {
    private val TAG = Log.tag(AvatarStore::class)
    private const val MAX_AVATAR_DOWNLOAD_BYTES = 10L * 1024 * 1024
    private const val TARGET_SIZE_PX = 128
  }

  private val appContext = context.applicationContext
  private val dir = File(context.filesDir, "avatars").apply { mkdirs() }

  // Group ids are base64 ('/', '+', '='); ACIs/PNIs pass through mostly unchanged.
  private fun fileForKey(key: String) = File(dir, key.replace(Regex("[^a-zA-Z0-9-]"), "_") + ".jpg")

  /** The cached avatar for [key] (ACI or group id), or null if none is cached. */
  fun fileFor(key: String): File? = fileForKey(key).takeIf { it.exists() }

  /**
   * Downloads and caches the profile avatar at [avatarPath] for contact [aci].
   * A null/empty path clears the cache (contact removed their photo). Never throws.
   */
  fun update(aci: String, profileKey: ProfileKey, avatarPath: String?) {
    try {
      if (avatarPath.isNullOrEmpty()) {
        clear(aci)
        return
      }

      val encrypted = File.createTempFile("avatar", ".tmp", dir)
      try {
        // downloadFromCdn appends, so the temp file must start empty (createTempFile guarantees it)
        AppDeps.net.authPushServiceSocket.retrieveProfileAvatar(avatarPath, encrypted, MAX_AVATAR_DOWNLOAD_BYTES)
        val decrypted = ProfileCipherInputStream(encrypted.inputStream(), profileKey).use { it.readBytes() }
        saveImage(aci, decrypted)
      } finally {
        encrypted.delete()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Avatar fetch for ${aci.take(8)} failed", t)
    }
  }

  /** Downscales and caches an already-decrypted image for [key]. Never throws. */
  fun saveImage(key: String, imageBytes: ByteArray) {
    try {
      val bitmap = decodeDownscaled(imageBytes) ?: run {
        Log.w(TAG, "Avatar for ${key.take(8)} did not decode; skipping")
        return
      }
      fileForKey(key).outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
      }
      bitmap.recycle()
      Log.i(TAG, "Cached avatar for ${key.take(8)}")
    } catch (t: Throwable) {
      Log.w(TAG, "Avatar save for ${key.take(8)} failed", t)
    }
  }

  fun clear(key: String) {
    fileForKey(key).delete()
  }

  /**
   * Fallback for 1:1 peers with no Signal profile photo: the device (address-book)
   * contact photo synced to the watch, matched by phone number via the directory cache.
   * Signal profile photos always win — this only fills keys that have no cached file.
   * Run after the profile resolvers each poll; cheap once photos are cached. Never throws.
   */
  fun backfillDeviceContactPhotos() {
    if (appContext.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return
    }

    try {
      val candidates = mutableListOf<Pair<String, String>>() // peer -> e164
      AppDeps.database.readableDatabase.rawQuery(
        "SELECT DISTINCT m.peer, d.e164 FROM messages m JOIN directory d ON d.aci = m.peer WHERE m.group_id IS NULL",
        emptyArray()
      ).use { cursor ->
        while (cursor.moveToNext()) {
          candidates += cursor.getString(0) to cursor.getString(1)
        }
      }

      for ((peer, e164) in candidates) {
        if (fileFor(peer) != null) continue
        val photo = deviceContactPhoto(e164) ?: continue
        saveImage(peer, photo)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Device contact photo backfill failed", t)
    }
  }

  private fun deviceContactPhoto(e164: String): ByteArray? {
    val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164))
    val contactUri = appContext.contentResolver.query(
      lookupUri,
      arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
      null,
      null,
      null
    )?.use { cursor ->
      if (!cursor.moveToFirst()) return null
      ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1))
    } ?: return null

    return ContactsContract.Contacts.openContactPhotoInputStream(appContext.contentResolver, contactUri, false)
      ?.use { it.readBytes() }
  }

  private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= TARGET_SIZE_PX && bounds.outHeight / (sampleSize * 2) >= TARGET_SIZE_PX) {
      sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }
}

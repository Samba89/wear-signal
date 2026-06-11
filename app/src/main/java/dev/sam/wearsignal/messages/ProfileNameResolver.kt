package dev.sam.wearsignal.messages

import android.content.ContentValues
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.runBlocking
import org.signal.core.models.ServiceId.ACI
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.crypto.ProfileCipher

/**
 * Resolves ACIs to display names using harvested profile keys: fetch the versioned
 * profile, decrypt the name with the profile key, and cache it in the contacts table.
 */
object ProfileNameResolver {

  private val TAG = Log.tag(ProfileNameResolver::class)
  private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

  /**
   * Fetches names for any senders that have a profile key but no (or stale) cached name.
   * Call while the websocket is usable; never throws.
   */
  fun resolvePending(senderAcis: Collection<String>) {
    if (senderAcis.isEmpty()) return

    val db = AppDeps.database
    val now = System.currentTimeMillis()

    for (aciString in senderAcis.distinct()) {
      try {
        val (profileKeyBytes, fetchedAt) = db.readableDatabase.rawQuery(
          "SELECT profile_key, fetched_at FROM contacts WHERE aci = ?",
          arrayOf(aciString)
        ).use { cursor ->
          if (!cursor.moveToFirst() || cursor.isNull(0)) continue
          cursor.getBlob(0) to cursor.getLong(1)
        }

        if (now - fetchedAt < REFRESH_INTERVAL_MS) continue

        val aci = ACI.parseOrNull(aciString) ?: continue
        val profileKey = ProfileKey(profileKeyBytes)

        val result = runBlocking { AppDeps.net.profileApi.getVersionedProfile(aci, profileKey, null) }
        if (result !is NetworkResult.Success) {
          Log.w(TAG, "Profile fetch for $aciString failed: $result")
          continue
        }

        val encryptedName = result.result.name
        val name = if (encryptedName.isNullOrEmpty()) {
          null
        } else {
          ProfileCipher(profileKey).decryptString(Base64.decode(encryptedName))
        }

        val values = ContentValues().apply {
          put("name", name?.replace('\u0000', ' ')?.trim())
          put("fetched_at", now)
        }
        db.writableDatabase.update("contacts", values, "aci = ?", arrayOf(aciString))
        Log.i(TAG, "Resolved profile name for $aciString")
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to resolve name for $aciString", t)
      }
    }
  }
}

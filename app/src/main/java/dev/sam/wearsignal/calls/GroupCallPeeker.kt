package dev.sam.wearsignal.calls

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.BuildConfig
import dev.sam.wearsignal.messages.GroupStateResolver
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.ringrtc.GroupCall
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Confirms "a group call may be happening" hints (recent groupCallUpdate eras) against
 * the SFU. A drained era only says a call started at some point since the last poll —
 * peeking is what tells us whether anyone is still in it.
 */
object GroupCallPeeker {

  private val TAG = Log.tag(GroupCallPeeker::class)

  /** How long after its last groupCallUpdate a group stays worth peeking. */
  private const val ERA_WINDOW_MS = 4L * 60 * 60 * 1000
  private const val CACHE_MS = 60_000L
  private const val PEEK_TIMEOUT_S = 10L

  private data class Cached(val at: Long, val joined: Int)

  private val cache = ConcurrentHashMap<String, Cached>()

  /**
   * Peeks every group with a recent call era and returns groupId → joined member count
   * for calls that still have participants. Blocking; call from a background thread.
   */
  fun refresh(): Map<String, Int> {
    if (!AppDeps.account.isLinked) return emptyMap()

    val now = System.currentTimeMillis()
    val candidates = mutableListOf<String>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT group_id FROM groups WHERE active_era_at > ?",
      arrayOf((now - ERA_WINDOW_MS).toString())
    ).use { cursor ->
      while (cursor.moveToNext()) candidates += cursor.getString(0)
    }
    if (candidates.isEmpty()) return emptyMap()

    val result = mutableMapOf<String, Int>()
    var connected = false
    var credential: org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse? = null
    try {
      for (groupId in candidates) {
        val cached = cache[groupId]
        if (cached != null && now - cached.at < CACHE_MS) {
          if (cached.joined > 0) result[groupId] = cached.joined
          continue
        }
        if (!connected) {
          AppDeps.net.authWebSocket.connect() // the GV2 credential fetch needs it
          connected = true
        }
        if (credential == null) {
          // One credential covers the whole day — fetch it once, not per group.
          credential = GroupStateResolver.todaysCredential() ?: return result
        }
        val joined = try {
          peek(groupId, credential)
        } catch (t: Throwable) {
          Log.w(TAG, "Peek failed for ${groupId.take(12)}", t)
          continue
        }
        cache[groupId] = Cached(now, joined)
        if (joined > 0) {
          result[groupId] = joined
        } else {
          // Call is over: forget the era so we stop peeking this group.
          AppDeps.database.writableDatabase.execSQL(
            "UPDATE groups SET active_era_at = 0 WHERE group_id = ?",
            arrayOf(groupId)
          )
        }
      }
    } finally {
      // Never tear the socket down while a call session (or a concurrent drain) owns it.
      if (connected && !CallEngine.isSessionActive) {
        try {
          AppDeps.net.authWebSocket.disconnect()
        } catch (_: Throwable) {
        }
      }
    }
    return result
  }

  private fun peek(groupId: String, credential: org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse): Int {
    val masterKey = AppDeps.database.readableDatabase.rawQuery(
      "SELECT master_key FROM groups WHERE group_id = ?",
      arrayOf(groupId)
    ).use { cursor ->
      if (!cursor.moveToFirst()) throw IOException("Unknown group")
      cursor.getBlob(0)
    }
    val memberAcis = GroupStateResolver.cachedAllMembers(groupId) ?: throw IOException("Members not fetched yet")

    val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
    val authorization = GroupStateResolver.authorizationString(secretParams, credential)
    val membershipProof = AppDeps.net.authPushServiceSocket
      .getExternalGroupCredential(authorization)
      .token
      .toByteArray(Charsets.UTF_8)

    val cipher = ClientZkGroupCipher(secretParams)
    val members = memberAcis.mapNotNull { raw ->
      (ServiceId.parseOrNull(raw) as? ServiceId.ACI)?.let { aci ->
        GroupCall.GroupMemberInfo(aci.rawUuid, cipher.encrypt(aci.libSignalServiceId).serialize())
      }
    }

    val latch = CountDownLatch(1)
    var joined = 0
    CallEngine.manager().peekGroupCall(BuildConfig.SIGNAL_SFU_URL, membershipProof, members) { info ->
      joined = info.joinedMembers.size
      latch.countDown()
    }
    if (!latch.await(PEEK_TIMEOUT_S, TimeUnit.SECONDS)) throw IOException("Peek timed out")
    return joined
  }
}

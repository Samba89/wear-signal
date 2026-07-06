package dev.sam.wearsignal.messages

import android.content.ContentValues
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves group ids to titles and member lists using the master keys harvested from
 * message contexts: fetch the group state from the GroupsV2 server (zkgroup auth
 * credential per day) and cache it in the groups table.
 */
object GroupStateResolver {

  private val TAG = Log.tag(GroupStateResolver::class)
  private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
  private const val MAX_AVATAR_DOWNLOAD_BYTES = 10L * 1024 * 1024

  /** Groups whose avatar was never attempted (pre-avatar rows to backfill). */
  fun pendingAvatarGroupIds(): List<String> {
    val result = mutableListOf<String>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT group_id FROM groups WHERE avatar_fetched_at = 0",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) result += cursor.getString(0)
    }
    return result
  }

  /**
   * Fetches state (title, members, photo) for any of the given groups whose cache is
   * missing or stale. Call while the websocket is usable; never throws.
   */
  fun resolvePending(groupIds: Collection<String>) {
    if (groupIds.isEmpty()) return

    val db = AppDeps.database
    val now = System.currentTimeMillis()
    var todaysCredential: AuthCredentialWithPniResponse? = null

    for (groupId in groupIds.distinct()) {
      try {
        val (masterKeyBytes, fetchedAt, avatarFetchedAt) = db.readableDatabase.rawQuery(
          "SELECT master_key, fetched_at, avatar_fetched_at FROM groups WHERE group_id = ?",
          arrayOf(groupId)
        ).use { cursor ->
          if (!cursor.moveToFirst()) continue
          Triple(cursor.getBlob(0), cursor.getLong(1), cursor.getLong(2))
        }

        // Refetch when the state is stale, or once to backfill the photo for groups
        // resolved before avatars existed.
        if (now - fetchedAt < REFRESH_INTERVAL_MS && avatarFetchedAt > 0) continue

        if (todaysCredential == null) {
          todaysCredential = fetchTodaysCredential() ?: return
        }

        val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKeyBytes))
        val authorization = AppDeps.net.groupsV2Api.getGroupsV2AuthorizationString(
          AppDeps.account.aci,
          AppDeps.account.pni,
          todaySeconds(),
          secretParams,
          todaysCredential
        )

        val group = AppDeps.net.groupsV2Api.getGroup(secretParams, authorization).group
        val members = DecryptedGroupUtil.toAciList(group.members).joinToString(",") { it.toString() }

        updateAvatar(groupId, secretParams, group.avatar)

        val values = ContentValues().apply {
          put("title", group.title.ifEmpty { null })
          put("members", members)
          put("revision", group.revision)
          put("fetched_at", now)
          put("avatar_fetched_at", now)
        }
        db.writableDatabase.update("groups", values, "group_id = ?", arrayOf(groupId))
        Log.i(TAG, "Resolved group state for ${groupId.take(12)}: ${group.members.size} member(s)")
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to resolve group ${groupId.take(12)}", t)
      }
    }
  }

  /** Downloads the group photo and decrypts it with the group's key material. Never throws. */
  private fun updateAvatar(groupId: String, secretParams: GroupSecretParams, avatarPath: String?) {
    try {
      if (avatarPath.isNullOrEmpty()) {
        AppDeps.avatars.clear(groupId)
        return
      }

      val encrypted = File.createTempFile("group-avatar", ".tmp")
      try {
        AppDeps.net.authPushServiceSocket.retrieveGroupsV2ProfileAvatar(avatarPath, encrypted, MAX_AVATAR_DOWNLOAD_BYTES)
        val imageBytes = AppDeps.net.groupsV2Operations.forGroup(secretParams).decryptAvatar(encrypted.readBytes())
        if (imageBytes.isNotEmpty()) {
          AppDeps.avatars.saveImage(groupId, imageBytes)
        } else {
          AppDeps.avatars.clear(groupId)
        }
      } finally {
        encrypted.delete()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Group avatar fetch for ${groupId.take(12)} failed", t)
    }
  }

  /** Cached member ACIs for a group (excluding self), or null if the state was never fetched. */
  fun cachedMembers(groupId: String): List<String>? {
    val selfAci = AppDeps.account.aci?.toString()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT members FROM groups WHERE group_id = ?",
      arrayOf(groupId)
    ).use { cursor ->
      if (!cursor.moveToFirst() || cursor.isNull(0)) return null
      return cursor.getString(0).split(",").filter { it.isNotEmpty() && it != selfAci }
    }
  }

  fun cachedTitle(groupId: String): String? {
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT title FROM groups WHERE group_id = ?",
      arrayOf(groupId)
    ).use { cursor ->
      return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
  }

  private fun todaySeconds(): Long = TimeUnit.DAYS.toSeconds(TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()))

  private fun fetchTodaysCredential(): AuthCredentialWithPniResponse? {
    val today = todaySeconds()
    val credentials = AppDeps.net.groupsV2Api.getCredentials(today).authCredentialWithPniResponseHashMap
    val credential = credentials[today]
    if (credential == null) {
      Log.w(TAG, "Credential response missing today's redemption time")
    }
    return credential
  }
}

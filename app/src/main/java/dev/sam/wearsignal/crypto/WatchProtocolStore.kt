package dev.sam.wearsignal.crypto

import android.content.ContentValues
import dev.sam.wearsignal.account.AccountStore
import dev.sam.wearsignal.db.WatchDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.push.DistributionId
import java.util.UUID

/**
 * SQLite-backed implementation of the Signal protocol stores for one account identity
 * ([accountId] is "aci" or "pni"). Trust-on-first-use identity policy, like Signal's own store.
 */
class WatchProtocolStore(
  private val db: WatchDatabase,
  private val account: AccountStore,
  private val accountId: String
) : SignalServiceAccountDataStore {

  // ===== IdentityKeyStore =====

  override fun getIdentityKeyPair(): IdentityKeyPair {
    return if (accountId == "pni") {
      account.pniIdentityKeyPair ?: error("No PNI identity")
    } else {
      account.aciIdentityKeyPair ?: error("No ACI identity")
    }
  }

  override fun getLocalRegistrationId(): Int {
    return if (accountId == "pni") account.pniRegistrationId else account.aciRegistrationId
  }

  override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
    val existing = getIdentity(address)
    if (existing == identityKey) {
      return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    val values = ContentValues().apply {
      put("account", accountId)
      put("address", address.name)
      put("identity_key", identityKey.serialize())
      put("added_at", System.currentTimeMillis())
    }
    db.writableDatabase.insertWithOnConflict("identities", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

    return if (existing == null) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
  }

  override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
    // Trust on first use; a changed key is accepted too (we're a notification-only receiver,
    // and the primary device is the place where safety numbers get verified).
    return true
  }

  override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
    db.readableDatabase.rawQuery(
      "SELECT identity_key FROM identities WHERE account = ? AND address = ?",
      arrayOf(accountId, address.name)
    ).use { cursor ->
      return if (cursor.moveToFirst()) IdentityKey(cursor.getBlob(0)) else null
    }
  }

  // ===== PreKeyStore =====

  @Throws(InvalidKeyIdException::class)
  override fun loadPreKey(preKeyId: Int): PreKeyRecord {
    db.readableDatabase.rawQuery(
      "SELECT record FROM one_time_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, preKeyId.toString())
    ).use { cursor ->
      if (cursor.moveToFirst()) return PreKeyRecord(cursor.getBlob(0))
    }
    throw InvalidKeyIdException("No prekey $preKeyId")
  }

  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
    val values = ContentValues().apply {
      put("account", accountId)
      put("key_id", preKeyId)
      put("record", record.serialize())
    }
    db.writableDatabase.insertWithOnConflict("one_time_prekeys", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
  }

  override fun containsPreKey(preKeyId: Int): Boolean {
    db.readableDatabase.rawQuery(
      "SELECT 1 FROM one_time_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, preKeyId.toString())
    ).use { return it.moveToFirst() }
  }

  override fun removePreKey(preKeyId: Int) {
    db.writableDatabase.delete("one_time_prekeys", "account = ? AND key_id = ?", arrayOf(accountId, preKeyId.toString()))
  }

  override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) {
    db.writableDatabase.execSQL(
      "UPDATE one_time_prekeys SET stale_at = ? WHERE account = ? AND stale_at = 0",
      arrayOf(staleTime, accountId)
    )
  }

  override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) {
    db.writableDatabase.execSQL(
      """
      DELETE FROM one_time_prekeys WHERE account = ?1 AND stale_at > 0 AND stale_at < ?2 AND key_id NOT IN (
        SELECT key_id FROM one_time_prekeys WHERE account = ?1 ORDER BY key_id DESC LIMIT ?3
      )
      """,
      arrayOf(accountId, threshold, minCount)
    )
  }

  // ===== SessionStore =====

  override fun loadSession(address: SignalProtocolAddress): SessionRecord {
    return getSession(address) ?: SessionRecord()
  }

  private fun getSession(address: SignalProtocolAddress): SessionRecord? {
    db.readableDatabase.rawQuery(
      "SELECT record FROM sessions WHERE account = ? AND address = ? AND device = ?",
      arrayOf(accountId, address.name, address.deviceId.toString())
    ).use { cursor ->
      return if (cursor.moveToFirst()) SessionRecord(cursor.getBlob(0)) else null
    }
  }

  @Throws(NoSessionException::class)
  override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
    return addresses.map { getSession(it) ?: throw NoSessionException("No session for $it") }
  }

  override fun getSubDeviceSessions(name: String): List<Int> {
    db.readableDatabase.rawQuery(
      "SELECT device FROM sessions WHERE account = ? AND address = ? AND device != 1",
      arrayOf(accountId, name)
    ).use { cursor ->
      val result = mutableListOf<Int>()
      while (cursor.moveToNext()) result += cursor.getInt(0)
      return result
    }
  }

  override fun getAllAddressesWithActiveSessions(addressNames: List<String>): Map<SignalProtocolAddress, SessionRecord> {
    if (addressNames.isEmpty()) return emptyMap()
    val result = mutableMapOf<SignalProtocolAddress, SessionRecord>()
    val placeholders = addressNames.joinToString(",") { "?" }
    db.readableDatabase.rawQuery(
      "SELECT address, device, record FROM sessions WHERE account = ? AND address IN ($placeholders)",
      (listOf(accountId) + addressNames).toTypedArray()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val record = SessionRecord(cursor.getBlob(2))
        // 0.0 = never treat a session as invalid for lacking PQ state (Signal's remote-config default)
        if (record.hasSenderChain(0.0)) {
          result[SignalProtocolAddress(cursor.getString(0), cursor.getInt(1))] = record
        }
      }
    }
    return result
  }

  override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
    val values = ContentValues().apply {
      put("account", accountId)
      put("address", address.name)
      put("device", address.deviceId)
      put("record", record.serialize())
    }
    db.writableDatabase.insertWithOnConflict("sessions", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
  }

  override fun containsSession(address: SignalProtocolAddress): Boolean {
    val record = getSession(address) ?: return false
    return record.hasSenderChain(0.0)
  }

  override fun deleteSession(address: SignalProtocolAddress) {
    db.writableDatabase.delete("sessions", "account = ? AND address = ? AND device = ?", arrayOf(accountId, address.name, address.deviceId.toString()))
  }

  override fun deleteAllSessions(name: String) {
    db.writableDatabase.delete("sessions", "account = ? AND address = ?", arrayOf(accountId, name))
  }

  override fun archiveSession(address: SignalProtocolAddress) {
    val session = getSession(address) ?: return
    session.archiveCurrentState()
    storeSession(address, session)
  }

  // ===== SignedPreKeyStore =====

  @Throws(InvalidKeyIdException::class)
  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
    db.readableDatabase.rawQuery(
      "SELECT record FROM signed_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, signedPreKeyId.toString())
    ).use { cursor ->
      if (cursor.moveToFirst()) return SignedPreKeyRecord(cursor.getBlob(0))
    }
    throw InvalidKeyIdException("No signed prekey $signedPreKeyId")
  }

  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
    db.readableDatabase.rawQuery(
      "SELECT record FROM signed_prekeys WHERE account = ?",
      arrayOf(accountId)
    ).use { cursor ->
      val result = mutableListOf<SignedPreKeyRecord>()
      while (cursor.moveToNext()) result += SignedPreKeyRecord(cursor.getBlob(0))
      return result
    }
  }

  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
    val values = ContentValues().apply {
      put("account", accountId)
      put("key_id", signedPreKeyId)
      put("record", record.serialize())
    }
    db.writableDatabase.insertWithOnConflict("signed_prekeys", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
  }

  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
    db.readableDatabase.rawQuery(
      "SELECT 1 FROM signed_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, signedPreKeyId.toString())
    ).use { return it.moveToFirst() }
  }

  override fun removeSignedPreKey(signedPreKeyId: Int) {
    db.writableDatabase.delete("signed_prekeys", "account = ? AND key_id = ?", arrayOf(accountId, signedPreKeyId.toString()))
  }

  // ===== KyberPreKeyStore =====

  @Throws(InvalidKeyIdException::class)
  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    db.readableDatabase.rawQuery(
      "SELECT record FROM kyber_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, kyberPreKeyId.toString())
    ).use { cursor ->
      if (cursor.moveToFirst()) return KyberPreKeyRecord(cursor.getBlob(0))
    }
    throw InvalidKeyIdException("No kyber prekey $kyberPreKeyId")
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    db.readableDatabase.rawQuery(
      "SELECT record FROM kyber_prekeys WHERE account = ?",
      arrayOf(accountId)
    ).use { cursor ->
      val result = mutableListOf<KyberPreKeyRecord>()
      while (cursor.moveToNext()) result += KyberPreKeyRecord(cursor.getBlob(0))
      return result
    }
  }

  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
    db.readableDatabase.rawQuery(
      "SELECT record FROM kyber_prekeys WHERE account = ? AND is_last_resort = 1",
      arrayOf(accountId)
    ).use { cursor ->
      val result = mutableListOf<KyberPreKeyRecord>()
      while (cursor.moveToNext()) result += KyberPreKeyRecord(cursor.getBlob(0))
      return result
    }
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    storeKyberPreKey(kyberPreKeyId, record, lastResort = false)
  }

  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) {
    storeKyberPreKey(kyberPreKeyId, kyberPreKeyRecord, lastResort = true)
  }

  private fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord, lastResort: Boolean) {
    val values = ContentValues().apply {
      put("account", accountId)
      put("key_id", kyberPreKeyId)
      put("record", record.serialize())
      put("is_last_resort", if (lastResort) 1 else 0)
    }
    db.writableDatabase.insertWithOnConflict("kyber_prekeys", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    db.readableDatabase.rawQuery(
      "SELECT 1 FROM kyber_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, kyberPreKeyId.toString())
    ).use { return it.moveToFirst() }
  }

  @Throws(ReusedBaseKeyException::class)
  override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedKeyId: Int, publicKey: ECPublicKey) {
    val isLastResort: Boolean = db.readableDatabase.rawQuery(
      "SELECT is_last_resort FROM kyber_prekeys WHERE account = ? AND key_id = ?",
      arrayOf(accountId, kyberPreKeyId.toString())
    ).use { cursor ->
      if (!cursor.moveToFirst()) return
      cursor.getInt(0) == 1
    }

    if (!isLastResort) {
      db.writableDatabase.delete("kyber_prekeys", "account = ? AND key_id = ?", arrayOf(accountId, kyberPreKeyId.toString()))
      return
    }

    // Last-resort keys are reusable, but the same (kyber, signed, baseKey) tuple must never repeat.
    val values = ContentValues().apply {
      put("account", accountId)
      put("kyber_key_id", kyberPreKeyId)
      put("signed_key_id", signedKeyId)
      put("base_key", publicKey.serialize())
    }
    val rowId = db.writableDatabase.insertWithOnConflict("used_kyber_tuples", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    if (rowId == -1L) {
      throw ReusedBaseKeyException("Repeated use of kyber prekey $kyberPreKeyId with same base key")
    }
  }

  override fun removeKyberPreKey(kyberPreKeyId: Int) {
    db.writableDatabase.delete("kyber_prekeys", "account = ? AND key_id = ?", arrayOf(accountId, kyberPreKeyId.toString()))
  }

  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {
    db.writableDatabase.execSQL(
      "UPDATE kyber_prekeys SET stale_at = ? WHERE account = ? AND stale_at = 0 AND is_last_resort = 0",
      arrayOf(staleTime, accountId)
    )
  }

  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {
    db.writableDatabase.execSQL(
      """
      DELETE FROM kyber_prekeys WHERE account = ?1 AND is_last_resort = 0 AND stale_at > 0 AND stale_at < ?2 AND key_id NOT IN (
        SELECT key_id FROM kyber_prekeys WHERE account = ?1 AND is_last_resort = 0 ORDER BY key_id DESC LIMIT ?3
      )
      """,
      arrayOf(accountId, threshold, minCount)
    )
  }

  // ===== SenderKeyStore =====

  override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
    val values = ContentValues().apply {
      put("account", accountId)
      put("address", sender.name)
      put("device", sender.deviceId)
      put("distribution_id", distributionId.toString())
      put("record", record.serialize())
      put("created_at", System.currentTimeMillis())
    }
    db.writableDatabase.insertWithOnConflict("sender_keys", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
  }

  override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
    db.readableDatabase.rawQuery(
      "SELECT record FROM sender_keys WHERE account = ? AND address = ? AND device = ? AND distribution_id = ?",
      arrayOf(accountId, sender.name, sender.deviceId.toString(), distributionId.toString())
    ).use { cursor ->
      return if (cursor.moveToFirst()) SenderKeyRecord(cursor.getBlob(0)) else null
    }
  }

  // Sender-key sharing state only matters for sending, which we never do.
  override fun getSenderKeySharedWith(distributionId: DistributionId): Set<SignalProtocolAddress> = emptySet()
  override fun markSenderKeySharedWith(distributionId: DistributionId, addresses: Collection<SignalProtocolAddress>) = Unit
  override fun clearSenderKeySharedWith(addresses: Collection<SignalProtocolAddress>) = Unit

  override fun isMultiDevice(): Boolean = true
}

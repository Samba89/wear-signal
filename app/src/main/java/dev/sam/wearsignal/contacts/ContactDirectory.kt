package dev.sam.wearsignal.contacts

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.signal.core.util.logging.Log
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.cds.CdsiV2Service
import org.whispersystems.signalservice.api.push.exceptions.CdsiResourceExhaustedException
import java.util.Optional

/**
 * The watch's address book of Signal-reachable contacts: the result of running the device's
 * contacts through CDSI once and caching the registered ones (phone number → ACI + name).
 *
 * CDSI is rate-limited, so we only hit it on an explicit [refresh] (first use / user-triggered);
 * the picker reads from the cached [all] in between.
 */
object ContactDirectory {

  private val TAG = Log.tag(ContactDirectory::class)

  /** [serviceId] is a ServiceId string: a bare-UUID ACI when known, else a "PNI:"-prefixed PNI. */
  data class Entry(val name: String, val e164: String, val serviceId: String)

  sealed interface RefreshResult {
    data class Success(val lookedUp: Int, val registered: Int) : RefreshResult
    data class RateLimited(val retryAfterSeconds: Int) : RefreshResult
    data class Failure(val message: String) : RefreshResult
  }

  /** Reads the watch's contacts, resolves them against CDSI, and caches the Signal-registered ones. */
  fun refresh(context: Context): RefreshResult {
    if (!AppDeps.account.isLinked) return RefreshResult.Failure("Not linked")

    val contacts = try {
      ContactReader.read(context)
    } catch (t: Throwable) {
      Log.w(TAG, "Reading contacts failed", t)
      return RefreshResult.Failure("Can't read contacts")
    }
    if (contacts.isEmpty()) return RefreshResult.Success(0, 0)

    val nameByNumber = contacts.associate { it.e164 to it.name }
    val webSocket = AppDeps.net.authWebSocket
    return try {
      webSocket.connect()
      val result = AppDeps.net.cdsApi.getRegisteredUsers(
        previousE164s = emptySet(),
        newE164s = nameByNumber.keys,
        // Profile keys of people we've chatted with: CDSI reveals the ACI of a number only
        // when the matching profile key proves prior contact. This upgrades "PNI:"-keyed
        // directory entries to ACIs once the person has messaged us.
        serviceIds = knownProfileKeys(),
        token = Optional.empty(),
        timeoutMs = 30_000,
        libsignalNetwork = AppDeps.net.libsignalNetwork
      ) {
        // One-off full lookup: no incremental token to persist.
      }

      when (result) {
        is NetworkResult.Success -> {
          val registered = cache(result.result.results, nameByNumber)
          Log.i(TAG, "Directory refreshed: ${nameByNumber.size} looked up, $registered on Signal")
          RefreshResult.Success(nameByNumber.size, registered)
        }

        is NetworkResult.StatusCodeError -> {
          when (val e = result.exception) {
            is CdsiResourceExhaustedException -> {
              Log.w(TAG, "CDSI rate limited; retry after ${e.retryAfterSeconds}s")
              RefreshResult.RateLimited(e.retryAfterSeconds)
            }
            else -> RefreshResult.Failure("Lookup failed (${result.code})")
          }
        }

        else -> RefreshResult.Failure("Lookup failed")
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Directory refresh failed", t)
      RefreshResult.Failure(t.message ?: "Lookup failed")
    } finally {
      webSocket.disconnect()
    }
  }

  /** ACI → profile key for everyone we've stored a profile key for (harvested from their messages). */
  private fun knownProfileKeys(): Map<ServiceId, ProfileKey> {
    val keys = mutableMapOf<ServiceId, ProfileKey>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT aci, profile_key FROM contacts WHERE profile_key IS NOT NULL",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        val serviceId = ServiceId.parseOrNull(cursor.getString(0)) ?: continue
        val profileKey = try {
          ProfileKey(cursor.getBlob(1))
        } catch (t: Throwable) {
          continue
        }
        keys[serviceId] = profileKey
      }
    }
    return keys
  }

  /** Writes registered contacts to the directory table; returns how many were registered. */
  private fun cache(
    results: Map<String, CdsiV2Service.ResponseItem>,
    nameByNumber: Map<String, String>
  ): Int {
    val db = AppDeps.database.writableDatabase
    var registered = 0
    // Snapshot the old send targets: a number that was "PNI:"-keyed and now resolves to an
    // ACI has its conversation folded into the ACI thread after the rewrite.
    val previousTarget = mutableMapOf<String, String>()
    db.rawQuery("SELECT e164, aci FROM directory", emptyArray()).use { cursor ->
      while (cursor.moveToNext()) {
        previousTarget[cursor.getString(0)] = cursor.getString(1)
      }
    }
    val merges = mutableListOf<Pair<String, String>>() // old PNI target -> new ACI target
    db.beginTransaction()
    try {
      db.delete("directory", null, null)
      for ((number, item) in results) {
        // CDSI returns a PNI for every registered number, but the ACI only for contacts whose
        // profile key we could present. Send to the ACI when we have it, otherwise to the
        // PNI — that's how Signal starts a first conversation by phone number.
        val aci = item.aci.orElse(null)
        val serviceId = (aci ?: item.pni) ?: continue
        val name = nameByNumber[number] ?: number
        upsert(db, number, serviceId.toString(), aci?.toString(), name)
        registered++
        val previous = previousTarget[number]
        if (aci != null && previous != null && previous != aci.toString() && previous.startsWith("PNI:")) {
          merges += previous to aci.toString()
        }
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    for ((pni, aci) in merges) {
      if (AppDeps.messages.mergePniIntoAci(pni, aci)) {
        Log.i(TAG, "Merged PNI conversation into ACI thread of ${aci.take(8)} after directory upgrade")
      }
    }
    return registered
  }

  private fun upsert(db: SQLiteDatabase, e164: String, serviceId: String, aci: String?, name: String) {
    db.insertWithOnConflict(
      "directory",
      null,
      ContentValues().apply {
        put("e164", e164)
        put("aci", serviceId) // column holds the send target: an ACI or a "PNI:"-prefixed PNI
        put("name", name)
      },
      SQLiteDatabase.CONFLICT_REPLACE
    )
    // Seed the contacts table (keyed by ACI) so incoming messages from this person are labelled
    // with their name, without clobbering a name already resolved from their Signal profile.
    // Only when we have a real ACI — the contacts table doesn't track PNIs.
    if (aci != null) {
      db.execSQL(
        "INSERT INTO contacts (aci, name, fetched_at) VALUES (?, ?, 0) " +
          "ON CONFLICT(aci) DO UPDATE SET name = COALESCE(contacts.name, excluded.name)",
        arrayOf(aci, name)
      )
    }
  }

  /** Cached Signal contacts, alphabetical. Empty until the first [refresh]. */
  fun all(): List<Entry> {
    val out = mutableListOf<Entry>()
    AppDeps.database.readableDatabase.rawQuery(
      "SELECT name, e164, aci FROM directory ORDER BY name COLLATE NOCASE ASC",
      emptyArray()
    ).use { cursor ->
      while (cursor.moveToNext()) {
        out += Entry(
          name = cursor.getString(0) ?: cursor.getString(1),
          e164 = cursor.getString(1),
          serviceId = cursor.getString(2)
        )
      }
    }
    return out
  }

  fun isEmpty(): Boolean {
    AppDeps.database.readableDatabase.rawQuery("SELECT 1 FROM directory LIMIT 1", emptyArray()).use { cursor ->
      return !cursor.moveToFirst()
    }
  }
}

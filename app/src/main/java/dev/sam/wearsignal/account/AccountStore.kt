package dev.sam.wearsignal.account

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.signal.core.util.Base64
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI

/**
 * Persistent storage for the linked account: identity, credentials, and app settings.
 */
class AccountStore(context: Context) {

  private val prefs: SharedPreferences = context.getSharedPreferences("account", Context.MODE_PRIVATE)

  val isLinked: Boolean
    get() = prefs.getString("aci", null) != null && deviceId > 0

  var aci: ACI?
    get() = prefs.getString("aci", null)?.let { ACI.parseOrNull(it) }
    set(value) = prefs.edit { putString("aci", value?.toString()) }

  var pni: PNI?
    get() = prefs.getString("pni", null)?.let { PNI.parseOrNull(it) }
    set(value) = prefs.edit { putString("pni", value?.toString()) }

  var e164: String?
    get() = prefs.getString("e164", null)
    set(value) = prefs.edit { putString("e164", value) }

  var deviceId: Int
    get() = prefs.getInt("device_id", -1)
    set(value) = prefs.edit { putInt("device_id", value) }

  var password: String?
    get() = prefs.getString("password", null)
    set(value) = prefs.edit { putString("password", value) }

  var aciIdentityKeyPair: IdentityKeyPair?
    get() = prefs.getString("aci_identity", null)?.let { IdentityKeyPair(Base64.decode(it)) }
    set(value) = prefs.edit { putString("aci_identity", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  var pniIdentityKeyPair: IdentityKeyPair?
    get() = prefs.getString("pni_identity", null)?.let { IdentityKeyPair(Base64.decode(it)) }
    set(value) = prefs.edit { putString("pni_identity", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  var profileKey: ProfileKey?
    get() = prefs.getString("profile_key", null)?.let { ProfileKey(Base64.decode(it)) }
    set(value) = prefs.edit { putString("profile_key", value?.let { Base64.encodeWithPadding(it.serialize()) }) }

  var aciRegistrationId: Int
    get() = prefs.getInt("aci_registration_id", 0)
    set(value) = prefs.edit { putInt("aci_registration_id", value) }

  var pniRegistrationId: Int
    get() = prefs.getInt("pni_registration_id", 0)
    set(value) = prefs.edit { putInt("pni_registration_id", value) }

  var lastPollAt: Long
    get() = prefs.getLong("last_poll_at", 0L)
    set(value) = prefs.edit { putLong("last_poll_at", value) }

  var lastSilentDrainAt: Long
    get() = prefs.getLong("last_silent_drain_at", 0L)
    set(value) = prefs.edit { putLong("last_silent_drain_at", value) }

  var lastPreKeyCheckAt: Long
    get() = prefs.getLong("last_prekey_check_at", 0L)
    set(value) = prefs.edit { putLong("last_prekey_check_at", value) }

  var pollIntervalMinutes: Int
    get() = prefs.getInt("poll_interval_minutes", 5)
    set(value) = prefs.edit { putInt("poll_interval_minutes", value) }

  /**
   * Whether to keep polling in the background on a recurring alarm. Off by default: in normal use
   * the phone bridges Signal's notifications to the watch, so background polling is pure battery
   * cost. Flip on (e.g. when the phone is dead) to use the watch as a standalone fallback.
   */
  var backgroundPollingEnabled: Boolean
    get() = prefs.getBoolean("background_polling_enabled", false)
    set(value) = prefs.edit { putBoolean("background_polling_enabled", value) }

  /** Debug override: pretend the phone is connected/disconnected. null = use real NodeClient state. */
  var phoneConnectedOverride: Boolean?
    get() = if (prefs.contains("phone_connected_override")) prefs.getBoolean("phone_connected_override", false) else null
    set(value) = prefs.edit { if (value == null) remove("phone_connected_override") else putBoolean("phone_connected_override", value) }

  fun clear() {
    prefs.edit { clear() }
  }
}

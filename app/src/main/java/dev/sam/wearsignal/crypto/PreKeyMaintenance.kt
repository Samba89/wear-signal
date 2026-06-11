package dev.sam.wearsignal.crypto

import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.push.ServiceIdType

/**
 * Periodic prekey upkeep, mirroring PreKeysSyncJob: top up one-time prekeys when the
 * server count drops below minimum, and rotate signed/last-resort keys every 2 days.
 */
object PreKeyMaintenance {

  private val TAG = Log.tag(PreKeyMaintenance::class)

  private const val ONE_TIME_PREKEY_MINIMUM = 10
  private val ROTATION_INTERVAL_MS = 2L * 24 * 60 * 60 * 1000

  /** Runs upkeep for both identities. Call with the websocket usable; never throws. */
  fun run() {
    try {
      val account = AppDeps.account
      val rotate = System.currentTimeMillis() - account.lastPreKeyCheckAt > ROTATION_INTERVAL_MS

      maintain(ServiceIdType.ACI, account.aciIdentityKeyPair ?: return, rotate)
      maintain(ServiceIdType.PNI, account.pniIdentityKeyPair ?: return, rotate)

      if (rotate) {
        account.lastPreKeyCheckAt = System.currentTimeMillis()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Prekey maintenance failed; will retry on a later drain", t)
    }
  }

  private fun maintain(serviceIdType: ServiceIdType, identityKeyPair: IdentityKeyPair, rotate: Boolean) {
    val store = if (serviceIdType == ServiceIdType.PNI) AppDeps.pniProtocolStore else AppDeps.aciProtocolStore

    val counts = when (val result = AppDeps.net.keysApi.getAvailablePreKeyCountsSync(serviceIdType)) {
      is NetworkResult.Success -> result.result
      else -> {
        Log.w(TAG, "[$serviceIdType] Could not fetch prekey counts: $result")
        return
      }
    }

    val ecPreKeys = if (counts.ecCount < ONE_TIME_PREKEY_MINIMUM) PreKeys.generateOneTimeEcPreKeys() else null
    val kyberPreKeys = if (counts.kyberCount < ONE_TIME_PREKEY_MINIMUM) PreKeys.generateOneTimeKyberPreKeys(identityKeyPair) else null
    val signedPreKey = if (rotate) PreKeys.generateSignedPreKey(PreKeys.generatePreKeyId(), identityKeyPair) else null
    val lastResort = if (rotate) PreKeys.generateKyberPreKey(PreKeys.generatePreKeyId(), identityKeyPair) else null

    if (ecPreKeys == null && kyberPreKeys == null && signedPreKey == null && lastResort == null) {
      Log.i(TAG, "[$serviceIdType] Prekeys healthy (ec=${counts.ecCount}, kyber=${counts.kyberCount}), nothing to do")
      return
    }

    ecPreKeys?.forEach { store.storePreKey(it.id, it) }
    kyberPreKeys?.forEach { store.storeKyberPreKey(it.id, it) }
    signedPreKey?.let { store.storeSignedPreKey(it.id, it) }
    lastResort?.let { store.storeLastResortKyberPreKey(it.id, it) }

    val upload = PreKeyUpload(
      serviceIdType = serviceIdType,
      signedPreKey = signedPreKey,
      oneTimeEcPreKeys = ecPreKeys,
      lastResortKyberPreKey = lastResort,
      oneTimeKyberPreKeys = kyberPreKeys
    )

    when (val result = AppDeps.net.keysApi.setPreKeysSync(upload)) {
      is NetworkResult.Success -> Log.i(TAG, "[$serviceIdType] Uploaded prekeys (rotated=$rotate, ec=${ecPreKeys?.size ?: 0}, kyber=${kyberPreKeys?.size ?: 0})")
      else -> Log.w(TAG, "[$serviceIdType] Prekey upload failed: $result")
    }
  }
}

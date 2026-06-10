package dev.sam.wearsignal.link

import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.crypto.DeviceNameCipher
import dev.sam.wearsignal.crypto.PreKeys
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.whispersystems.signalservice.api.account.AccountAttributes
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import java.security.SecureRandom

/**
 * Completes the linking after the primary device has scanned the QR and the
 * provisioning socket has delivered the decrypted [ProvisionMessage].
 */
object LinkingRepository {

  private val TAG = Log.tag(LinkingRepository::class)

  const val DEVICE_NAME = "Watch"

  sealed interface LinkResult {
    data object Success : LinkResult
    data class Failure(val message: String) : LinkResult
  }

  fun completeLinking(message: ProvisionMessage): LinkResult {
    val account = AppDeps.account

    val aci = message.aciBinary?.let { ACI.parseOrThrow(it.toByteArray()) } ?: ACI.parseOrThrow(message.aci)
    val pni = message.pniBinary?.let { PNI.parseOrThrow(it.toByteArray()) } ?: PNI.parseOrThrow(message.pni)
    val e164 = message.number ?: return LinkResult.Failure("Provision message missing number")
    val provisioningCode = message.provisioningCode ?: return LinkResult.Failure("Provision message missing code")

    val aciIdentityKeyPair = IdentityKeyPair(
      IdentityKey(message.aciIdentityKeyPublic!!.toByteArray()),
      ECPrivateKey(message.aciIdentityKeyPrivate!!.toByteArray())
    )
    val pniIdentityKeyPair = IdentityKeyPair(
      IdentityKey(message.pniIdentityKeyPublic!!.toByteArray()),
      ECPrivateKey(message.pniIdentityKeyPrivate!!.toByteArray())
    )
    val profileKey = ProfileKey(message.profileKey!!.toByteArray())

    val password = generatePassword()
    val aciRegistrationId = PreKeys.generateRegistrationId()
    val pniRegistrationId = PreKeys.generateRegistrationId()

    val encryptedDeviceName = DeviceNameCipher.encryptDeviceName(DEVICE_NAME.toByteArray(Charsets.UTF_8), aciIdentityKeyPair)

    val accountAttributes = AccountAttributes(
      signalingKey = null,
      registrationId = aciRegistrationId,
      fetchesMessages = true, // no FCM; we poll via websocket
      registrationLock = null,
      unidentifiedAccessKey = UnidentifiedAccess.deriveAccessKeyFrom(profileKey),
      unrestrictedUnidentifiedAccess = false,
      capabilities = AccountAttributes.Capabilities(
        storage = false,
        versionedExpirationTimer = true,
        attachmentBackfill = true,
        spqr = true,
        usernameChangeSyncMessage = false
      ),
      discoverableByPhoneNumber = false,
      name = Base64.encodeWithPadding(encryptedDeviceName),
      pniRegistrationId = pniRegistrationId,
      recoveryPassword = null
    )

    val aciPreKeys = PreKeys.generateSignedAndLastResortPreKeys(aciIdentityKeyPair)
    val pniPreKeys = PreKeys.generateSignedAndLastResortPreKeys(pniIdentityKeyPair)

    Log.i(TAG, "Registering as secondary device...")
    val result = AppDeps.net.unauthenticatedRegistrationApi(e164, password)
      .registerAsSecondaryDevice(provisioningCode, accountAttributes, aciPreKeys, pniPreKeys, null)

    when (result) {
      is NetworkResult.Success -> {
        val deviceId = result.result.deviceId.toInt()
        Log.i(TAG, "Linked! deviceId=$deviceId")

        account.apply {
          this.aci = aci
          this.pni = pni
          this.e164 = e164
          this.password = password
          this.deviceId = deviceId
          this.aciIdentityKeyPair = aciIdentityKeyPair
          this.pniIdentityKeyPair = pniIdentityKeyPair
          this.profileKey = profileKey
          this.aciRegistrationId = aciRegistrationId
          this.pniRegistrationId = pniRegistrationId
        }

        AppDeps.aciProtocolStore.storeSignedPreKey(aciPreKeys.signedPreKey.id, aciPreKeys.signedPreKey)
        AppDeps.aciProtocolStore.storeLastResortKyberPreKey(aciPreKeys.lastResortKyberPreKey.id, aciPreKeys.lastResortKyberPreKey)
        AppDeps.pniProtocolStore.storeSignedPreKey(pniPreKeys.signedPreKey.id, pniPreKeys.signedPreKey)
        AppDeps.pniProtocolStore.storeLastResortKyberPreKey(pniPreKeys.lastResortKyberPreKey.id, pniPreKeys.lastResortKyberPreKey)

        return try {
          uploadOneTimePreKeys(ServiceIdType.ACI, aciIdentityKeyPair)
          uploadOneTimePreKeys(ServiceIdType.PNI, pniIdentityKeyPair)
          LinkResult.Success
        } catch (e: Exception) {
          // Linked but prekey upload failed; last-resort keys keep us decryptable, retry later.
          Log.w(TAG, "One-time prekey upload failed; continuing", e)
          LinkResult.Success
        } finally {
          AppDeps.net.authWebSocket.disconnect()
        }
      }

      is NetworkResult.ApplicationError -> {
        Log.w(TAG, "Application error during linking", result.throwable)
        return LinkResult.Failure("Unexpected error: ${result.throwable.message}")
      }
      is NetworkResult.NetworkError -> {
        Log.w(TAG, "Network error during linking", result.exception)
        return LinkResult.Failure("Network error: ${result.exception.message}")
      }
      is NetworkResult.StatusCodeError -> {
        Log.w(TAG, "Status code error during linking: ${result.code}")
        val reason = when (result.code) {
          403 -> "Incorrect verification"
          409 -> "Missing account capability"
          411 -> "Too many linked devices (max 5)"
          422 -> "Invalid request"
          429 -> "Rate limited, try again later"
          else -> "Server error ${result.code}"
        }
        return LinkResult.Failure(reason)
      }
    }
  }

  private fun uploadOneTimePreKeys(serviceIdType: ServiceIdType, identityKeyPair: IdentityKeyPair) {
    val store = if (serviceIdType == ServiceIdType.PNI) AppDeps.pniProtocolStore else AppDeps.aciProtocolStore

    val ecPreKeys = PreKeys.generateOneTimeEcPreKeys()
    val kyberPreKeys = PreKeys.generateOneTimeKyberPreKeys(identityKeyPair)

    ecPreKeys.forEach { store.storePreKey(it.id, it) }
    kyberPreKeys.forEach { store.storeKyberPreKey(it.id, it) }

    Log.i(TAG, "Uploading one-time prekeys for $serviceIdType")
    AppDeps.net.keysApi.setPreKeysSync(
      PreKeyUpload(
        serviceIdType = serviceIdType,
        signedPreKey = null,
        oneTimeEcPreKeys = ecPreKeys,
        lastResortKyberPreKey = null,
        oneTimeKyberPreKeys = kyberPreKeys
      )
    ).successOrThrow()
  }

  private fun generatePassword(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeWithPadding(bytes)
  }
}

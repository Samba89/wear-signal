/*
 * Copied from Signal-Android (org.thoughtcrime.securesms.registration.secondary.DeviceNameCipher),
 * AGPL-3.0-only. Encrypts the linked-device name against the account's ACI identity key.
 */
package dev.sam.wearsignal.crypto

import dev.sam.wearsignal.protos.DeviceName
import okio.ByteString.Companion.toByteString
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DeviceNameCipher {

  private const val SYNTHETIC_IV_LENGTH = 16

  @JvmStatic
  fun encryptDeviceName(plaintext: ByteArray, identityKeyPair: IdentityKeyPair): ByteArray {
    val ephemeralKeyPair: ECKeyPair = ECKeyPair.generate()
    val masterSecret: ByteArray = ephemeralKeyPair.privateKey.calculateAgreement(identityKeyPair.publicKey.publicKey)

    val syntheticIv: ByteArray = computeSyntheticIv(masterSecret, plaintext)
    val cipherKey: ByteArray = computeCipherKey(masterSecret, syntheticIv)

    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(ByteArray(16)))
    val cipherText = cipher.doFinal(plaintext)

    return DeviceName(
      ephemeralPublic = ephemeralKeyPair.publicKey.serialize().toByteString(),
      syntheticIv = syntheticIv.toByteString(),
      ciphertext = cipherText.toByteString()
    ).encode()
  }

  private fun computeCipherKey(masterSecret: ByteArray, syntheticIv: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
    val cipherKeyPart1 = mac.doFinal("cipher".toByteArray())

    mac.init(SecretKeySpec(cipherKeyPart1, "HmacSHA256"))
    return mac.doFinal(syntheticIv)
  }

  private fun computeSyntheticIv(masterSecret: ByteArray, plaintext: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
    val syntheticIvPart1 = mac.doFinal("auth".toByteArray())

    mac.init(SecretKeySpec(syntheticIvPart1, "HmacSHA256"))
    return mac.doFinal(plaintext).copyOf(SYNTHETIC_IV_LENGTH)
  }
}

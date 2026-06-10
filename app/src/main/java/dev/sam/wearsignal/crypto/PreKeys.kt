package dev.sam.wearsignal.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.account.PreKeyCollection
import java.security.SecureRandom

/**
 * Prekey generation, mirroring Signal-Android's PreKeyUtil / RegistrationRepository helpers.
 */
object PreKeys {

  const val ONE_TIME_BATCH_SIZE = 100

  fun generatePreKeyId(): Int = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1

  fun generateRegistrationId(): Int = SecureRandom().nextInt(16380) + 1

  fun generateSignedPreKey(id: Int, identityKeyPair: IdentityKeyPair, timestamp: Long = System.currentTimeMillis()): SignedPreKeyRecord {
    val keyPair = ECKeyPair.generate()
    val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
    return SignedPreKeyRecord(id, timestamp, keyPair, signature)
  }

  fun generateKyberPreKey(id: Int, identityKeyPair: IdentityKeyPair, timestamp: Long = System.currentTimeMillis()): KyberPreKeyRecord {
    val kemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    val signature = identityKeyPair.privateKey.calculateSignature(kemKeyPair.publicKey.serialize())
    return KyberPreKeyRecord(id, timestamp, kemKeyPair, signature)
  }

  /** Signed prekey + last-resort kyber prekey, as needed for the /v1/devices/link call. */
  fun generateSignedAndLastResortPreKeys(identityKeyPair: IdentityKeyPair): PreKeyCollection {
    return PreKeyCollection(
      identityKey = identityKeyPair.publicKey,
      signedPreKey = generateSignedPreKey(generatePreKeyId(), identityKeyPair),
      lastResortKyberPreKey = generateKyberPreKey(generatePreKeyId(), identityKeyPair)
    )
  }

  /** Batch of one-time EC prekeys with sequential ids starting at a random offset. */
  fun generateOneTimeEcPreKeys(): List<PreKeyRecord> {
    val baseId = SecureRandom().nextInt(Int.MAX_VALUE - ONE_TIME_BATCH_SIZE - 1) + 1
    return (0 until ONE_TIME_BATCH_SIZE).map { i ->
      PreKeyRecord(baseId + i, ECKeyPair.generate())
    }
  }

  /** Batch of one-time kyber prekeys with sequential ids starting at a random offset. */
  fun generateOneTimeKyberPreKeys(identityKeyPair: IdentityKeyPair): List<KyberPreKeyRecord> {
    val baseId = SecureRandom().nextInt(Int.MAX_VALUE - ONE_TIME_BATCH_SIZE - 1) + 1
    return (0 until ONE_TIME_BATCH_SIZE).map { i ->
      generateKyberPreKey(baseId + i, identityKeyPair)
    }
  }
}

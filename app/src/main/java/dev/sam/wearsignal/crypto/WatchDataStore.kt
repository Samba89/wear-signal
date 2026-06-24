package dev.sam.wearsignal.crypto

import dev.sam.wearsignal.AppDeps
import org.signal.core.models.ServiceId
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore

/**
 * Multi-account wrapper that hands SignalServiceMessageSender the ACI / PNI protocol stores.
 */
object WatchDataStore : SignalServiceDataStore {

  override fun get(accountIdentifier: ServiceId): SignalServiceAccountDataStore {
    return if (accountIdentifier == AppDeps.account.pni) AppDeps.pniProtocolStore else AppDeps.aciProtocolStore
  }

  override fun aci(): SignalServiceAccountDataStore = AppDeps.aciProtocolStore

  override fun pni(): SignalServiceAccountDataStore = AppDeps.pniProtocolStore

  override fun isMultiDevice(): Boolean = true
}

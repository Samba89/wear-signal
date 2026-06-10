package dev.sam.wearsignal

import android.content.Context
import dev.sam.wearsignal.account.AccountStore
import dev.sam.wearsignal.crypto.WatchProtocolStore
import dev.sam.wearsignal.db.WatchDatabase
import dev.sam.wearsignal.net.SignalNet

/**
 * Lazy app-wide singletons.
 */
object AppDeps {

  private lateinit var appContext: Context

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  val account: AccountStore by lazy { AccountStore(appContext) }
  val database: WatchDatabase by lazy { WatchDatabase(appContext) }
  val net: SignalNet by lazy { SignalNet(appContext, account) }
  val aciProtocolStore: WatchProtocolStore by lazy { WatchProtocolStore(database, account, "aci") }
  val pniProtocolStore: WatchProtocolStore by lazy { WatchProtocolStore(database, account, "pni") }
}

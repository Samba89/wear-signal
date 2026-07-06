package dev.sam.wearsignal

import android.content.Context
import dev.sam.wearsignal.account.AccountStore
import dev.sam.wearsignal.contacts.AvatarStore
import dev.sam.wearsignal.crypto.WatchProtocolStore
import dev.sam.wearsignal.db.WatchDatabase
import dev.sam.wearsignal.messages.AttachmentStore
import dev.sam.wearsignal.messages.EnvelopeProcessor
import dev.sam.wearsignal.messages.MessageRetriever
import dev.sam.wearsignal.messages.MessagesRepository
import dev.sam.wearsignal.net.SignalNet
import dev.sam.wearsignal.notify.NotificationPresenter

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
  val messages: MessagesRepository by lazy { MessagesRepository(database) }
  val retriever: MessageRetriever by lazy { MessageRetriever(EnvelopeProcessor(messages)) }
  val notifier: NotificationPresenter by lazy { NotificationPresenter(appContext) }
  val avatars: AvatarStore by lazy { AvatarStore(appContext) }
  val attachments: AttachmentStore by lazy { AttachmentStore(appContext) }
}

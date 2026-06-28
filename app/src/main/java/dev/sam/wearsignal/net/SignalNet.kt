package dev.sam.wearsignal.net

import android.content.Context
import dev.sam.wearsignal.BuildConfig
import dev.sam.wearsignal.account.AccountStore
import dev.sam.wearsignal.crypto.SessionLock
import dev.sam.wearsignal.crypto.WatchDataStore
import org.signal.core.util.UptimeSleepTimer
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations
import org.whispersystems.signalservice.api.keys.KeysApi
import org.whispersystems.signalservice.api.keys.PreKeyRepository
import org.whispersystems.signalservice.api.message.MessageApi
import org.whispersystems.signalservice.api.profiles.ProfileApi
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.whispersystems.signalservice.api.push.TrustStore
import org.whispersystems.signalservice.api.registration.RegistrationApi
import org.whispersystems.signalservice.api.util.CredentialsProvider
import org.whispersystems.signalservice.api.websocket.HealthMonitor
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.api.websocket.WebSocketFactory
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl
import org.whispersystems.signalservice.internal.configuration.SignalCdsiUrl
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl
import org.whispersystems.signalservice.internal.configuration.SignalStorageUrl
import org.whispersystems.signalservice.internal.configuration.SignalSvr2Url
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider
import org.whispersystems.signalservice.internal.websocket.LibSignalChatConnection
import org.whispersystems.signalservice.internal.websocket.applyConfiguration
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier

/**
 * Network singletons: service configuration, libsignal Network, websockets, and APIs.
 */
class SignalNet(context: Context, private val account: AccountStore) {

  private val appContext = context.applicationContext

  private val trustStore: TrustStore = object : TrustStore {
    override fun getKeyStoreInputStream(): InputStream = appContext.resources.openRawResource(dev.sam.wearsignal.R.raw.whisper)
    override fun getKeyStorePassword(): String = "whisper"
  }

  val configuration: SignalServiceConfiguration by lazy {
    SignalServiceConfiguration(
      signalServiceUrls = arrayOf(SignalServiceUrl(BuildConfig.SIGNAL_URL, trustStore)),
      signalCdnUrlMap = mapOf(
        0 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN_URL, trustStore)),
        2 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN2_URL, trustStore)),
        3 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN3_URL, trustStore))
      ),
      signalStorageUrls = arrayOf(SignalStorageUrl(BuildConfig.STORAGE_URL, trustStore)),
      signalCdsiUrls = arrayOf(SignalCdsiUrl(BuildConfig.SIGNAL_CDSI_URL, trustStore)),
      signalSvr2Urls = arrayOf(SignalSvr2Url(BuildConfig.SIGNAL_SVR2_URL, trustStore)),
      networkInterceptors = emptyList(),
      dns = Optional.empty(),
      signalProxy = Optional.empty(),
      systemHttpProxy = Optional.empty(),
      zkGroupServerPublicParams = org.signal.core.util.Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS),
      genericServerPublicParams = org.signal.core.util.Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS),
      backupServerPublicParams = org.signal.core.util.Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS),
      censored = false
    )
  }

  val libsignalNetwork: Network by lazy {
    Network(Network.Environment.PRODUCTION, BuildConfig.SIGNAL_AGENT, emptyMap(), Network.BuildVariant.PRODUCTION).also {
      it.applyConfiguration(configuration)
    }
  }

  /** Credentials sourced live from [AccountStore], so the same websocket factory works before and after linking. */
  private val credentialsProvider: CredentialsProvider = object : CredentialsProvider {
    override fun getAci(): ACI? = account.aci
    override fun getPni(): PNI? = account.pni
    override fun getE164(): String? = account.e164
    override fun getDeviceId(): Int = account.deviceId
    override fun getPassword(): String? = account.password
  }

  private val healthMonitor = object : HealthMonitor {
    override fun onKeepAliveResponse(sentTimestamp: Long, isIdentifiedWebSocket: Boolean) = Unit
    override fun onMessageError(status: Int, isIdentifiedWebSocket: Boolean) = Unit
    override fun onReceivedAlerts(alerts: Array<out String>, isIdentifiedWebSocket: Boolean) = Unit
  }

  val authWebSocket: SignalWebSocket.AuthenticatedWebSocket by lazy {
    val factory = WebSocketFactory {
      LibSignalChatConnection("libsignal-auth", libsignalNetwork, credentialsProvider, false, healthMonitor)
    }
    SignalWebSocket.AuthenticatedWebSocket(factory, { true }, UptimeSleepTimer(), 30_000)
  }

  val unauthWebSocket: SignalWebSocket.UnauthenticatedWebSocket by lazy {
    val factory = WebSocketFactory {
      LibSignalChatConnection("libsignal-unauth", libsignalNetwork, null, false, healthMonitor)
    }
    SignalWebSocket.UnauthenticatedWebSocket(factory, { true }, UptimeSleepTimer(), 30_000)
  }

  val keysApi: KeysApi by lazy { KeysApi(authWebSocket, unauthWebSocket) }

  val cdsApi: CdsApi by lazy { CdsApi(authWebSocket) }

  private val authPushServiceSocket: PushServiceSocket by lazy {
    PushServiceSocket(configuration, credentialsProvider, BuildConfig.SIGNAL_AGENT, false)
  }

  val profileApi: ProfileApi by lazy {
    ProfileApi(
      authWebSocket,
      unauthWebSocket,
      authPushServiceSocket,
      ClientZkOperations.create(configuration).profileOperations
    )
  }

  val messageApi: MessageApi by lazy { MessageApi(authWebSocket, unauthWebSocket) }

  val messageSender: SignalServiceMessageSender by lazy {
    val selfAddress = SignalProtocolAddress(account.aci!!.libSignalServiceId, account.deviceId)
    val preKeyRepository = PreKeyRepository(
      keysApi,
      WatchDataStore.aci(),
      selfAddress,
      PreKeyRepository.BatchHelper { it.run() }
    )

    SignalServiceMessageSender(
      authPushServiceSocket,
      WatchDataStore,
      SessionLock,
      messageApi,
      keysApi,
      Optional.empty(),
      Executors.newFixedThreadPool(4),
      256 * 1024L,
      10,
      BooleanSupplier { false },
      preKeyRepository
    )
  }

  /** For the pre-link registration call: basic auth is e164:password, deviceId -1 (mirrors AccountManagerFactory.createUnauthenticated). */
  fun unauthenticatedRegistrationApi(e164: String, password: String): RegistrationApi {
    val credentials = StaticCredentialsProvider(null, null, e164, -1, password)
    return RegistrationApi(PushServiceSocket(configuration, credentials, BuildConfig.SIGNAL_AGENT, false))
  }
}

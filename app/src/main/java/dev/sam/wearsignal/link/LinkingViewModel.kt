package dev.sam.wearsignal.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import java.io.Closeable

/**
 * Maintains the provisioning websocket and runs registration once the primary scans the QR.
 * Modeled on Signal-Android's RegisterLinkDeviceQrViewModel.
 */
class LinkingViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(LinkingViewModel::class)
  }

  sealed interface LinkState {
    data object LoadingQr : LinkState
    data class ShowingQr(val url: String) : LinkState
    data object Registering : LinkState
    data object Done : LinkState
    data class Error(val message: String) : LinkState
  }

  private val store: MutableStateFlow<LinkState> = MutableStateFlow(LinkState.LoadingQr)
  val state: StateFlow<LinkState> = store

  private val socketHandles: MutableList<Closeable> = mutableListOf()
  private var refreshJob: Job? = null

  init {
    restart()
  }

  fun restart() {
    shutdown()
    store.value = LinkState.LoadingQr
    startNewSocket()

    refreshJob = viewModelScope.launch(Dispatchers.IO) {
      var count = 0
      while (count < 5 && isActive) {
        delay(ProvisioningSocket.LIFESPAN / 2)
        if (isActive && store.value !is LinkState.Registering && store.value !is LinkState.Done) {
          startNewSocket()
          count++
        }
      }
    }
  }

  private fun startNewSocket() {
    synchronized(socketHandles) {
      socketHandles += startSocket()
      if (socketHandles.size > 2) {
        socketHandles.removeAt(0).close()
      }
    }
  }

  private fun startSocket(): Closeable {
    return ProvisioningSocket.start<ProvisionMessage>(
      mode = ProvisioningSocket.Mode.LINK,
      identityKeyPair = IdentityKeyPair.generate(),
      configuration = AppDeps.net.configuration,
      handler = { id, t ->
        Log.w(TAG, "Provisioning socket [$id] failed", t)
        store.update { current ->
          if (current is LinkState.ShowingQr || current is LinkState.LoadingQr) LinkState.Error("Connection failed") else current
        }
      }
    ) { socket ->
      val url = socket.getProvisioningUrl()
      Log.i(TAG, "Provisioning URL ready (socket ${socket.id}): $url")
      store.update { current ->
        if (current is LinkState.Registering || current is LinkState.Done) current else LinkState.ShowingQr(url)
      }

      val result = socket.getProvisioningMessageDecryptResult()

      if (result is SecondaryProvisioningCipher.ProvisioningDecryptResult.Success) {
        Log.i(TAG, "Provisioning message received on socket ${socket.id}")
        store.value = LinkState.Registering

        // Registration must run on viewModelScope: shutdown() cancels the provisioning
        // socket scope this block executes in, which would kill an inline registration.
        viewModelScope.launch(Dispatchers.IO) {
          val linkResult = LinkingRepository.completeLinking(result.message)

          store.value = when (linkResult) {
            is LinkingRepository.LinkResult.Success -> LinkState.Done
            is LinkingRepository.LinkResult.Failure -> LinkState.Error(linkResult.message)
          }
        }

        shutdown()
      } else {
        Log.w(TAG, "Provisioning decrypt failed on socket ${socket.id}")
        store.value = LinkState.Error("Could not decrypt provisioning message")
      }
    }
  }

  private fun shutdown() {
    refreshJob?.cancel()
    synchronized(socketHandles) {
      socketHandles.forEach { it.close() }
      socketHandles.clear()
    }
  }

  override fun onCleared() {
    shutdown()
  }
}

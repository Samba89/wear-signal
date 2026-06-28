package dev.sam.wearsignal.poll

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.core.util.logging.Log

/**
 * Detects whether the paired phone is currently reachable (Bluetooth/Wi-Fi direct connection).
 * When it is, the phone's own Signal app bridges notifications to the watch, so we stay silent.
 */
object PhoneConnectionMonitor {

  private val TAG = Log.tag(PhoneConnectionMonitor::class)

  suspend fun isPhoneConnected(context: Context): Boolean {
    AppDeps.account.phoneConnectedOverride?.let {
      Log.i(TAG, "Using phoneConnectedOverride=$it")
      return it
    }

    return try {
      withTimeoutOrNull(5_000) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        // Any connected node, not just isNearby: the phone bridges Signal's notifications to the
        // watch over Bluetooth *or* Wi-Fi/cloud, but isNearby is true only for a direct BT link.
        // Filtering on isNearby wrongly reported "phone away" on a Wi-Fi bridge → double notifications.
        nodes.isNotEmpty()
      } ?: false
    } catch (t: Throwable) {
      Log.w(TAG, "Node lookup failed; assuming phone disconnected", t)
      false
    }
  }
}

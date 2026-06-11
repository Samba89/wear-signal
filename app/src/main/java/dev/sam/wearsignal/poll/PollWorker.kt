package dev.sam.wearsignal.poll

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.crypto.PreKeyMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

/**
 * One scheduled poll cycle.
 *
 * Phone connected: stay silent (phone Signal's notifications bridge to the watch natively),
 * but once a day drain the queue silently to keep it short, refresh the 45-day linked-device
 * deadline, and run prekey upkeep.
 *
 * Phone away: drain and notify.
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private val TAG = Log.tag(PollWorker::class)
    private val SILENT_DRAIN_INTERVAL_MS = 24L * 60 * 60 * 1000
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      if (!AppDeps.account.isLinked) return@withContext Result.success()

      val phoneConnected = PhoneConnectionMonitor.isPhoneConnected(applicationContext)

      if (phoneConnected) {
        val sinceLastDrain = System.currentTimeMillis() - AppDeps.account.lastSilentDrainAt
        if (sinceLastDrain > SILENT_DRAIN_INTERVAL_MS) {
          Log.i(TAG, "Phone connected; running daily silent drain + prekey upkeep")
          Poller.poll(silent = true)
          PreKeyMaintenance.run()
          AppDeps.net.authWebSocket.disconnect()
          AppDeps.account.lastSilentDrainAt = System.currentTimeMillis()
        } else {
          Log.i(TAG, "Phone connected; skipping poll")
        }
      } else {
        Poller.poll(silent = false)
      }

      Result.success()
    } catch (t: Throwable) {
      Log.w(TAG, "Poll failed", t)
      Result.success() // next alarm fires regardless; don't let WorkManager backoff fight the schedule
    } finally {
      PollScheduler.scheduleNext(applicationContext)
    }
  }
}

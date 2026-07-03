package dev.sam.wearsignal.poll

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.sam.wearsignal.AppDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

/**
 * One scheduled poll cycle (only runs while the notification toggle is on).
 *
 * Phone connected: skip — phone Signal's notifications bridge to the watch natively.
 * Phone away: drain and notify. Daily upkeep lives in [MaintenanceWorker].
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private val TAG = Log.tag(PollWorker::class)
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      if (!AppDeps.account.isLinked) return@withContext Result.success()

      if (PhoneConnectionMonitor.isPhoneConnected(applicationContext)) {
        Log.i(TAG, "Phone connected; skipping poll")
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

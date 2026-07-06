package dev.sam.wearsignal.poll

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.crypto.PreKeyMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import java.util.concurrent.TimeUnit

/**
 * Daily upkeep, run only while the watch is charging so it costs no wearing-time battery:
 * silently drain the message queue (populates conversations and refreshes the server's
 * 45-day linked-device inactivity deadline) and run prekey upkeep. Independent of the
 * notification toggle — this must keep running even when background polling is off.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private val TAG = Log.tag(MaintenanceWorker::class)
    private const val WORK_NAME = "daily-maintenance"

    /** Idempotent: keeps any existing schedule. Call at app start and after linking. */
    fun ensureScheduled(context: Context) {
      val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
        .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    if (!AppDeps.account.isLinked) return@withContext Result.success()

    try {
      Log.i(TAG, "Running charge-time maintenance: silent drain + prekey upkeep")
      Poller.poll(silent = true)
      PreKeyMaintenance.run()
      AppDeps.account.lastSilentDrainAt = System.currentTimeMillis()
    } catch (t: Throwable) {
      Log.w(TAG, "Maintenance failed; will retry next charge window", t)
    } finally {
      AppDeps.net.authWebSocket.disconnect()
    }

    Result.success()
  }
}

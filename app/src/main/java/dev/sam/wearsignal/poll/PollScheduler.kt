package dev.sam.wearsignal.poll

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import java.util.concurrent.TimeUnit

/**
 * Schedules the next poll. Prefers exact alarms (1/5/15 min user choice); falls back to a
 * 15-minute WorkManager periodic job if exact alarms are not permitted.
 */
object PollScheduler {

  private val TAG = Log.tag(PollScheduler::class)
  private const val WORK_NAME = "poll-fallback"

  private fun pollPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, PollReceiver::class.java),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  fun scheduleNext(context: Context) {
    if (!AppDeps.account.isLinked) return
    if (!AppDeps.account.backgroundPollingEnabled) {
      cancel(context)
      return
    }

    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intervalMs = AppDeps.account.pollIntervalMinutes * 60_000L

    if (alarmManager.canScheduleExactAlarms()) {
      alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, pollPendingIntent(context))
      Log.i(TAG, "Scheduled exact poll in ${AppDeps.account.pollIntervalMinutes} min")
    } else {
      Log.w(TAG, "Exact alarms not permitted; using 15-min WorkManager fallback")
      val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }

  /** Cancels any pending recurring poll (alarm + WorkManager fallback). */
  fun cancel(context: Context) {
    context.getSystemService(AlarmManager::class.java).cancel(pollPendingIntent(context))
    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    Log.i(TAG, "Background polling cancelled")
  }
}

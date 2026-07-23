package dev.sam.wearsignal.tile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.poll.Poller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

/**
 * Silent drain kicked off when the tile is viewed, so its data is current even when
 * background polling is off. Throttled: viewing the tile repeatedly within
 * [MIN_INTERVAL_MS] of a successful poll is served from the local store only.
 * [Poller.poll] pushes the refreshed data back to the tile when it finishes.
 */
class TilePollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  companion object {
    private val TAG = Log.tag(TilePollWorker::class)
    private const val MIN_INTERVAL_MS = 2 * 60 * 1000L

    fun enqueue(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork(
        "tile-poll",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<TilePollWorker>().build()
      )
    }
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      val sinceLastPoll = System.currentTimeMillis() - AppDeps.account.lastPollAt
      if (AppDeps.account.isLinked && sinceLastPoll > MIN_INTERVAL_MS) {
        Poller.poll(silent = true)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Tile poll failed", t)
    }
    Result.success()
  }
}

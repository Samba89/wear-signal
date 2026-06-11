package dev.sam.wearsignal.poll

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/** Alarm target: hands the actual work to WorkManager so we get a proper wakelock window. */
class PollReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val request = OneTimeWorkRequestBuilder<PollWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .build()
    WorkManager.getInstance(context).enqueue(request)
  }
}

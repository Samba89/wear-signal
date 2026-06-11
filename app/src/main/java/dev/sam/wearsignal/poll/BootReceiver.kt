package dev.sam.wearsignal.poll

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the poll schedule after reboot. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      PollScheduler.scheduleNext(context)
    }
  }
}

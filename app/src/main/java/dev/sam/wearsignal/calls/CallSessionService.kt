package dev.sam.wearsignal.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.poll.Poller
import dev.sam.wearsignal.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log

/**
 * Foreground service alive for the duration of a call session. Owns the user-facing
 * surface: the ongoing-call notification (with a full-screen intent so incoming rings
 * take over the screen) and the ringtone/vibration while ringing.
 */
class CallSessionService : Service() {

  companion object {
    private val TAG = Log.tag(CallSessionService::class)
    private const val CHANNEL_ONGOING = "ongoing_call"
    private const val NOTIFICATION_ID = 787

    fun start(context: Context) {
      try {
        context.startForegroundService(Intent(context, CallSessionService::class.java))
      } catch (t: Throwable) {
        // Background-start restrictions can bite when a fresh offer arrives from a drain
        // without an exemption; the call still works if the app UI is open.
        Log.w(TAG, "Couldn't start call foreground service", t)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, CallSessionService::class.java))
    }
  }

  private val scope = CoroutineScope(Dispatchers.Main + Job())
  private var ringtone: Ringtone? = null
  private var started = false

  /** peer → display name, resolved once per session (notification rebuilds per state change). */
  private var cachedName: Pair<String, String>? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val channel = NotificationChannel(CHANNEL_ONGOING, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
      description = "Ongoing Signal calls"
      setSound(null, null) // ringing is done manually so it can loop and stop precisely
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      "answer" -> {
        CallEngine.accept()
        startActivity(
          Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
      }
      "decline", "hangup" -> CallEngine.hangup()
    }
    if (!started) {
      started = true
      try {
        startForeground(
          NOTIFICATION_ID,
          buildNotification(CallEngine.state.value),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
      } catch (t: Throwable) {
        // Mic-typed foreground starts are restricted from the background on newer
        // Android; fall back to the call type alone (the mic engages after the user
        // interacts anyway) rather than crashing mid-drain.
        Log.w(TAG, "Mic-typed foreground start rejected; retrying as phoneCall only", t)
        try {
          startForeground(NOTIFICATION_ID, buildNotification(CallEngine.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (t2: Throwable) {
          Log.w(TAG, "Foreground start rejected outright", t2)
          stopSelf()
          return START_NOT_STICKY
        }
      }
      scope.launch {
        // The service can start before RingRTC flips the state away from Idle, so only
        // treat Idle as "session over" once a session was actually observed.
        var sawSession = false
        CallEngine.state.collect { state ->
          when (state) {
            is CallState.Idle -> if (sawSession) stopSelf()
            is CallState.Incoming -> {
              sawSession = true
              startRinging()
              getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
            else -> {
              sawSession = true
              stopRinging()
              getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
          }
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopRinging()
    scope.coroutineContext[Job]?.cancel()
    super.onDestroy()
  }

  private fun buildNotification(state: CallState): android.app.Notification {
    val peer = when (state) {
      is CallState.Incoming -> state.peer
      is CallState.Outgoing -> state.peer
      is CallState.Active -> state.peer
      is CallState.Ended -> state.peer
      else -> ""
    }
    val name = when {
      peer.isEmpty() -> "Signal call"
      cachedName?.first == peer -> cachedName!!.second
      else -> Poller.resolveName(peer).also { cachedName = Pair(peer, it) }
    }
    val person = Person.Builder().setName(name).build()

    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, CallActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ONGOING)
      .setSmallIcon(android.R.drawable.sym_call_incoming)
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)

    when (state) {
      is CallState.Incoming -> {
        val decline = servicePendingIntent("decline")
        val answer = servicePendingIntent("answer")
        builder
          .setContentTitle(if (state.video) "Incoming video call" else "Incoming call")
          .setContentText(name)
          .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
          .setFullScreenIntent(contentIntent, true)
      }
      is CallState.Active -> {
        builder
          .setContentTitle("Ongoing call")
          .setContentText(name)
          .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, servicePendingIntent("hangup")))
      }
      else -> {
        builder
          .setContentTitle("Calling…")
          .setContentText(name)
          .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, servicePendingIntent("hangup")))
      }
    }
    return builder.build()
  }

  private fun servicePendingIntent(action: String): PendingIntent {
    val intent = Intent(this, CallSessionService::class.java).setAction(action)
    return PendingIntent.getService(
      this,
      action.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun startRinging() {
    if (ringtone != null) return
    try {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
        audioAttributes = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .build()
        isLooping = true
        play()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Ringtone failed", t)
    }
    try {
      getSystemService(Vibrator::class.java)
        ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
    } catch (t: Throwable) {
      Log.w(TAG, "Vibration failed", t)
    }
  }

  private fun stopRinging() {
    try {
      ringtone?.stop()
    } catch (_: Throwable) {
    }
    ringtone = null
    try {
      getSystemService(Vibrator::class.java)?.cancel()
    } catch (_: Throwable) {
    }
  }
}

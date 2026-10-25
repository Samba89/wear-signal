package dev.sam.wearsignal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.R
import dev.sam.wearsignal.messages.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import java.io.File

private const val TAG = "VoiceRecordScreen"

/** Longest voice note we record; the recorder stops itself here. */
private const val MAX_DURATION_MS = 5 * 60 * 1000

/** Anything shorter is treated as an accidental tap and discarded. */
private const val MIN_DURATION_MS = 500L

private val RECORD_RED = Color(0xFFE53935)

private enum class Phase { Idle, Recording, Recorded, Sending }

/**
 * Records a voice message for [peer] (group id when [isGroup]) and sends it.
 * Tap to record (timer counts up, capped at five minutes), tap to stop; then play it back,
 * delete it to re-record, or send it. [onSent] fires once the message went out.
 *
 * Records AAC (ADTS, mono, 32 kbps) — the same format Signal-Android records — into the cache
 * dir; the recording is deleted on delete, after sending, and when leaving the screen.
 */
@Composable
fun VoiceRecordScreen(title: String, peer: String, isGroup: Boolean, onSent: () -> Unit) {
  val context = LocalContext.current
  val view = LocalView.current
  val scope = rememberCoroutineScope()

  var phase by remember { mutableStateOf(Phase.Idle) }
  var error by remember { mutableStateOf<String?>(null) }
  var elapsedMs by remember { mutableLongStateOf(0L) } // recording length so far / final length
  var progress by remember { mutableFloatStateOf(0f) } // upload fraction while sending

  val recording = remember { VoiceRecording(context) }
  val preview = remember { PreviewPlayer() }

  fun stopRecording() {
    if (phase != Phase.Recording) return
    val lengthMs = recording.stop()
    elapsedMs = lengthMs
    if (lengthMs < MIN_DURATION_MS) {
      recording.discard()
      phase = Phase.Idle
      error = "Too short — tap and speak"
    } else {
      phase = Phase.Recorded
      preview.load(recording.file)
    }
  }

  fun startRecording() {
    error = null
    elapsedMs = 0L
    if (recording.start(onMaxDuration = { stopRecording() })) {
      phase = Phase.Recording
    } else {
      error = "Couldn't start the microphone"
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      startRecording()
    } else {
      error = "Microphone permission denied"
    }
  }

  fun onRecordTap() {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      startRecording()
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun delete() {
    preview.release()
    recording.discard()
    elapsedMs = 0L
    error = null
    phase = Phase.Idle
  }

  fun send() {
    val file = recording.file ?: return
    preview.pause()
    error = null
    progress = 0f
    phase = Phase.Sending
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        MessageSender.sendVoiceNote(peer, isGroup, file, onProgress = { progress = it }).also {
          // Here rather than after withContext, so it still runs if the screen is left mid-send.
          if (it is MessageSender.Result.Success) file.delete()
        }
      }
      when (result) {
        is MessageSender.Result.Success -> {
          recording.forget()
          onSent()
        }
        is MessageSender.Result.Failure -> {
          // Keep the recording so Send can be retried.
          error = result.message
          phase = Phase.Recorded
        }
      }
    }
  }

  // Timer tick while recording; the recorder's own max-duration stop is the primary cap.
  LaunchedEffect(phase) {
    while (phase == Phase.Recording) {
      elapsedMs = recording.elapsedMs()
      if (elapsedMs >= MAX_DURATION_MS) stopRecording()
      delay(200)
    }
  }

  // The watch screen timing out mid-recording would pause us; keep it on while recording.
  DisposableEffect(phase) {
    view.keepScreenOn = phase == Phase.Recording || phase == Phase.Sending
    onDispose { view.keepScreenOn = false }
  }

  DisposableEffect(Unit) {
    onDispose {
      preview.release()
      recording.stop()
      // A send still in flight owns the file and deletes it on success.
      if (phase != Phase.Sending) recording.discard()
    }
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.caption2,
      color = Color(0xFF9E9E9E),
      textAlign = TextAlign.Center,
      maxLines = 1,
      modifier = Modifier.fillMaxWidth()
    )

    when (phase) {
      Phase.Idle, Phase.Recording -> RecordControls(
        recording = phase == Phase.Recording,
        elapsedMs = elapsedMs,
        onTap = { if (phase == Phase.Recording) stopRecording() else onRecordTap() }
      )
      Phase.Recorded -> RecordedControls(
        preview = preview,
        durationMs = elapsedMs,
        onDelete = ::delete,
        onSend = ::send
      )
      Phase.Sending -> SendingIndicator(progress)
    }

    Text(
      text = error?.let { "⚠ $it" } ?: when (phase) {
        Phase.Idle -> "Tap to record"
        Phase.Recording -> "Tap to stop · max ${formatDuration(MAX_DURATION_MS.toLong())}"
        Phase.Recorded -> ""
        Phase.Sending -> "Sending…"
      },
      style = MaterialTheme.typography.caption3,
      color = if (error != null) Color(0xFFFFAB91) else Color(0xFF9E9E9E),
      textAlign = TextAlign.Center,
      maxLines = 2,
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
  }
}

@Composable
private fun RecordControls(recording: Boolean, elapsedMs: Long, onTap: () -> Unit) {
  Text(
    text = formatDuration(elapsedMs),
    style = MaterialTheme.typography.display3,
    color = if (recording) RECORD_RED else Color.White,
    modifier = Modifier.padding(vertical = 6.dp)
  )
  Button(
    onClick = onTap,
    colors = ButtonDefaults.buttonColors(backgroundColor = RECORD_RED),
    modifier = Modifier.size(ButtonDefaults.LargeButtonSize)
  ) {
    if (recording) {
      // Stop: a white rounded square.
      Box(modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
    } else {
      Icon(
        painter = painterResource(R.drawable.ic_mic),
        contentDescription = "Start recording",
        modifier = Modifier.size(28.dp)
      )
    }
  }
}

@Composable
private fun RecordedControls(preview: PreviewPlayer, durationMs: Long, onDelete: () -> Unit, onSend: () -> Unit) {
  var playing by remember { mutableStateOf(false) }
  var positionMs by remember { mutableLongStateOf(0L) }

  LaunchedEffect(playing) {
    while (playing) {
      positionMs = preview.positionMs()
      if (!preview.isPlaying()) {
        playing = false
        positionMs = 0L
      }
      delay(100)
    }
  }

  val total = preview.durationMs().takeIf { it > 0 } ?: durationMs
  Text(
    text = if (playing || positionMs > 0) "${formatDuration(positionMs)} / ${formatDuration(total)}" else formatDuration(total),
    style = MaterialTheme.typography.title2,
    modifier = Modifier.padding(vertical = 8.dp)
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    Button(
      onClick = {
        playing = false
        onDelete()
      },
      colors = ButtonDefaults.secondaryButtonColors(),
      modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
    ) {
      Text("🗑")
    }
    Button(
      onClick = {
        if (playing) {
          preview.pause()
          playing = false
        } else {
          playing = preview.play()
        }
      },
      colors = ButtonDefaults.secondaryButtonColors(),
      modifier = Modifier.padding(horizontal = 8.dp).size(ButtonDefaults.DefaultButtonSize)
    ) {
      Text(if (playing) "❚❚" else "▶", style = MaterialTheme.typography.title3)
    }
    Button(
      onClick = {
        playing = false
        onSend()
      },
      colors = ButtonDefaults.primaryButtonColors(),
      modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
    ) {
      Text("➤")
    }
  }
}

@Composable
private fun SendingIndicator(progress: Float) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp).size(64.dp)) {
    if (progress > 0f) {
      CircularProgressIndicator(progress = progress, modifier = Modifier.fillMaxSize())
      Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.caption1)
    } else {
      CircularProgressIndicator(modifier = Modifier.fillMaxSize())
    }
  }
}

private fun formatDuration(ms: Long): String {
  val totalSeconds = ms / 1000
  return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** One MediaRecorder session writing to a cache file. Not thread-safe; use from the main thread. */
private class VoiceRecording(private val context: Context) {

  private var recorder: MediaRecorder? = null
  private var startedAt = 0L
  private var stoppedLengthMs = 0L

  /** The current (or last stopped) recording, until discarded or sent. */
  var file: File? = null
    private set

  fun start(onMaxDuration: () -> Unit): Boolean {
    discard()
    val dir = File(context.cacheDir, "voice").apply { mkdirs() }
    // Leftovers from a send interrupted by the app being killed; nothing else holds them.
    val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000
    dir.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
    val output = File(dir, "voice_${System.currentTimeMillis()}.aac")
    val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(context)
    } else {
      @Suppress("DEPRECATION")
      MediaRecorder()
    }
    return try {
      mediaRecorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(32_000)
        setMaxDuration(MAX_DURATION_MS)
        setOutputFile(output.path)
        setOnInfoListener { _, what, _ ->
          if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
        }
        prepare()
        start()
      }
      recorder = mediaRecorder
      file = output
      startedAt = SystemClock.elapsedRealtime()
      true
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't start recording", t)
      mediaRecorder.release()
      output.delete()
      false
    }
  }

  fun elapsedMs(): Long = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else stoppedLengthMs

  /** Stops recording (no-op if not recording) and returns its length; 0 when nothing usable was captured. */
  fun stop(): Long {
    val active = recorder ?: return stoppedLengthMs
    recorder = null
    stoppedLengthMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(MAX_DURATION_MS.toLong())
    try {
      active.stop()
    } catch (t: RuntimeException) {
      // Thrown when stopped before any audio was encoded, or after the max-duration auto-stop.
      Log.w(TAG, "Recorder stop failed", t)
    } finally {
      active.release()
    }
    if ((file?.length() ?: 0L) == 0L) stoppedLengthMs = 0L
    return stoppedLengthMs
  }

  /** Stops and deletes the recording. */
  fun discard() {
    stop()
    file?.delete()
    file = null
    stoppedLengthMs = 0L
  }

  /** Drops our reference without deleting (the file was sent and is handled by the sender). */
  fun forget() {
    file = null
  }
}

/** Plays back the recording before sending. Self-contained MediaPlayer wrapper; main thread only. */
private class PreviewPlayer {

  private var player: MediaPlayer? = null

  fun load(file: File?) {
    release()
    if (file == null) return
    player = try {
      MediaPlayer().apply {
        setDataSource(file.path)
        prepare()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Couldn't load preview", t)
      null
    }
  }

  /** Starts (or resumes) playback; restarts from the top after it finished. Returns whether it's playing. */
  fun play(): Boolean {
    val mediaPlayer = player ?: return false
    if (mediaPlayer.currentPosition >= mediaPlayer.duration - 50) mediaPlayer.seekTo(0)
    mediaPlayer.start()
    return true
  }

  fun pause() {
    player?.takeIf { it.isPlaying }?.pause()
  }

  fun isPlaying(): Boolean = player?.isPlaying == true

  fun positionMs(): Long = player?.currentPosition?.toLong() ?: 0L

  fun durationMs(): Long = player?.duration?.toLong()?.takeIf { it > 0 } ?: 0L

  fun release() {
    player?.release()
    player = null
  }
}

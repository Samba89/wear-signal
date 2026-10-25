package dev.sam.wearsignal.ui

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log

private val TAG = Log.tag(VoicePlayback::class)

/**
 * Process-wide "one plays at a time": starting a player pauses whichever one was
 * playing before. Main-thread only (Compose callbacks).
 */
private object VoicePlayback {
  private var current: (() -> Unit)? = null

  /** Registers [pause] as the playing player, pausing the previous one. */
  fun claim(pause: () -> Unit) {
    current?.takeIf { it !== pause }?.invoke()
    current = pause
  }

  fun release(pause: () -> Unit) {
    if (current === pause) current = null
  }
}

/**
 * Compact audio player for a local file (voice notes, audio attachments): a play/pause
 * button, a progress bar, and elapsed / total time (m:ss). Uses [MediaPlayer]; the
 * player is prepared lazily on first play and released when this leaves composition.
 * Only one player plays at a time.
 */
@Composable
fun VoiceNotePlayer(path: String, contentColor: Color, modifier: Modifier = Modifier) {
  var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
  var playing by remember(path) { mutableStateOf(false) }
  var failed by remember(path) { mutableStateOf(false) }
  var positionMs by remember(path) { mutableIntStateOf(0) }
  var durationMs by remember(path) { mutableIntStateOf(0) }

  val pause: () -> Unit = remember(path) {
    {
      player?.takeIf { it.isPlaying }?.pause()
      playing = false
    }
  }

  DisposableEffect(path) {
    onDispose {
      VoicePlayback.release(pause)
      player?.release()
      player = null
      playing = false
    }
  }

  // Total length up front, so it shows before the first play.
  LaunchedEffect(path) {
    val probed = withContext(Dispatchers.IO) { probeDurationMs(path) }
    if (durationMs == 0) durationMs = probed
  }

  // Poll the position while playing; MediaPlayer has no progress callback.
  LaunchedEffect(playing, player) {
    val p = player ?: return@LaunchedEffect
    while (playing) {
      positionMs = runCatching { p.currentPosition }.getOrDefault(positionMs)
      delay(200)
    }
  }

  fun toggle() {
    if (playing) {
      pause()
      return
    }
    val p = player ?: MediaPlayer().let { created ->
      try {
        created.setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        created.setDataSource(path)
        created.prepare() // local file: fast enough to do synchronously
      } catch (t: Throwable) {
        Log.w(TAG, "Can't play $path", t)
        created.release()
        failed = true
        return
      }
      created.setOnCompletionListener {
        playing = false
        positionMs = 0
        it.seekTo(0)
        VoicePlayback.release(pause)
      }
      if (created.duration > 0) durationMs = created.duration
      player = created
      created
    }
    VoicePlayback.claim(pause)
    p.start()
    playing = true
  }

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Button(
      onClick = { toggle() },
      enabled = !failed,
      colors = ButtonDefaults.buttonColors(
        backgroundColor = contentColor.copy(alpha = 0.2f),
        contentColor = contentColor
      ),
      modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize)
    ) {
      PlayPauseIcon(playing = playing, color = contentColor)
    }
    Spacer(modifier = Modifier.width(6.dp))
    Column(modifier = Modifier.width(76.dp)) {
      val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
      Canvas(modifier = Modifier.fillMaxWidth().height(3.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(contentColor.copy(alpha = 0.3f), cornerRadius = radius)
        drawRoundRect(contentColor, size = Size(size.width * progress, size.height), cornerRadius = radius)
      }
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = when {
          failed -> "Can't play"
          playing || positionMs > 0 -> "${formatMs(positionMs)} / ${formatMs(durationMs)}"
          else -> formatMs(durationMs)
        },
        style = MaterialTheme.typography.caption3,
        color = contentColor.copy(alpha = 0.7f)
      )
    }
  }
}

/** Play triangle / pause bars, drawn so they take the bubble's content colour. */
@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color) {
  Canvas(modifier = Modifier.size(12.dp)) {
    val w = size.width
    val h = size.height
    if (playing) {
      val bar = w * 0.32f
      drawRect(color, topLeft = Offset(w * 0.1f, 0f), size = Size(bar, h))
      drawRect(color, topLeft = Offset(w * 0.9f - bar, 0f), size = Size(bar, h))
    } else {
      val path = Path().apply {
        moveTo(w * 0.15f, 0f)
        lineTo(w, h / 2f)
        lineTo(w * 0.15f, h)
        close()
      }
      drawPath(path, color)
    }
  }
}

/** Duration from the file's metadata without preparing a player; 0 when unknown. */
private fun probeDurationMs(path: String): Int = try {
  MediaMetadataRetriever().use { retriever ->
    retriever.setDataSource(path)
    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
  }
} catch (t: Throwable) {
  0
}

private fun formatMs(ms: Int): String {
  val totalSeconds = ms / 1000
  return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

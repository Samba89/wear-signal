package dev.sam.wearsignal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.calls.CallEngine
import dev.sam.wearsignal.calls.CallState
import dev.sam.wearsignal.poll.Poller
import kotlinx.coroutines.delay

/**
 * The in-call surface: incoming ring (accept/decline), outgoing progress, and the
 * active call (mute/hang up). Launched by the user from a thread, or by the call
 * notification's full-screen intent when a live ring arrives.
 */
class CallActivity : ComponentActivity() {

  companion object {
    const val EXTRA_OUTGOING_PEER = "outgoing_peer"
  }

  private var micGranted = mutableStateOf(false)

  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    micGranted.value = granted
    if (granted) {
      maybeStartOutgoing()
    } else if (intent.hasExtra(EXTRA_OUTGOING_PEER)) {
      finish() // can't place a call without a mic
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    micGranted.value = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    // Only a fresh launch may dial: a recreated activity re-delivers the original
    // intent (removeExtra doesn't persist) and must not silently redial the peer.
    if (savedInstanceState != null) {
      intent.removeExtra(EXTRA_OUTGOING_PEER)
    }
    if (micGranted.value) {
      maybeStartOutgoing()
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    setContent {
      MaterialTheme {
        val state by CallEngine.state.collectAsState()
        // Close when the session ends — but only once one existed, since an outgoing
        // launch composes before startOutgoing has flipped the state away from Idle.
        var sawSession by remember { mutableStateOf(false) }
        LaunchedEffect(state) {
          if (state !is CallState.Idle) {
            sawSession = true
          } else if (sawSession) {
            finish()
          }
        }
        CallScreen(
          state = state,
          micGranted = micGranted.value,
          onAccept = { CallEngine.accept() },
          onHangup = { CallEngine.hangup() },
          onToggleMute = { CallEngine.toggleMute() }
        )
      }
    }
  }

  /** singleTask: a call button tapped while an instance exists lands here, not onCreate. */
  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (micGranted.value) {
      maybeStartOutgoing()
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  /** Launched from a thread with an extra: place the call once the mic is granted. */
  private fun maybeStartOutgoing() {
    val peer = intent.getStringExtra(EXTRA_OUTGOING_PEER) ?: return
    intent.removeExtra(EXTRA_OUTGOING_PEER)
    CallEngine.startOutgoing(peer)
  }
}

@Composable
private fun CallScreen(
  state: CallState,
  micGranted: Boolean,
  onAccept: () -> Unit,
  onHangup: () -> Unit,
  onToggleMute: () -> Unit
) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    when (state) {
      is CallState.Idle -> Text("…")

      is CallState.Incoming -> {
        CallHeader(
          peer = state.peer,
          line = if (state.video) "Incoming video call" else "Incoming call",
          sub = if (state.video) "answers with camera off" else null
        )
        if (!micGranted) {
          Text(
            text = "Microphone permission needed",
            style = MaterialTheme.typography.caption3,
            color = Color(0xFFFFAB91),
            textAlign = TextAlign.Center
          )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
          RoundAction("✕", Color(0xFFB71C1C), onHangup)
          Spacer(modifier = Modifier.width(20.dp))
          RoundAction("✓", Color(0xFF1B5E20), onAccept, enabled = micGranted)
        }
      }

      is CallState.Outgoing -> {
        CallHeader(peer = state.peer, line = if (state.ringing) "Ringing…" else "Calling…", sub = null)
        Spacer(modifier = Modifier.height(10.dp))
        RoundAction("✕", Color(0xFFB71C1C), onHangup)
      }

      is CallState.Active -> {
        CallHeader(peer = state.peer, line = elapsedLabel(state.connectedAt), sub = if (state.video) "video off" else null)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
          RoundAction(
            label = if (state.muted) "🔇" else "🎙",
            color = if (state.muted) Color(0xFF616161) else Color(0xFF37474F),
            onClick = onToggleMute
          )
          Spacer(modifier = Modifier.width(20.dp))
          RoundAction("✕", Color(0xFFB71C1C), onHangup)
        }
      }

      is CallState.Ended -> {
        CallHeader(peer = state.peer, line = state.reason, sub = null)
      }
    }
  }
}

@Composable
private fun CallHeader(peer: String, line: String, sub: String?) {
  val name = remember(peer) { Poller.resolveName(peer) }
  Avatar(name = name, colorKey = peer, avatarKey = peer, size = 36.dp)
  Spacer(modifier = Modifier.height(4.dp))
  Text(
    text = name,
    style = MaterialTheme.typography.title3,
    textAlign = TextAlign.Center
  )
  Text(
    text = line,
    style = MaterialTheme.typography.caption2,
    color = Color(0xFF9E9E9E),
    textAlign = TextAlign.Center
  )
  if (sub != null) {
    Text(
      text = sub,
      style = MaterialTheme.typography.caption3,
      color = Color(0xFF757575),
      textAlign = TextAlign.Center
    )
  }
}

/** Live mm:ss ticker for the active call. */
@Composable
private fun elapsedLabel(connectedAt: Long): String {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(connectedAt) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }
  val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
  return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun RoundAction(label: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(backgroundColor = color),
    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
  ) {
    Text(label)
  }
}

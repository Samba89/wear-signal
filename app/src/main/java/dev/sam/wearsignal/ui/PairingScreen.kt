package dev.sam.wearsignal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.link.LinkingViewModel

/**
 * Shows the provisioning QR for the phone's Signal app to scan
 * (Settings -> Linked devices -> Link new device).
 */
@Composable
fun PairingScreen(viewModel: LinkingViewModel, onLinked: () -> Unit) {
  val state by viewModel.state.collectAsState()

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    when (val s = state) {
      is LinkingViewModel.LinkState.LoadingQr -> {
        CircularProgressIndicator()
      }

      is LinkingViewModel.LinkState.ShowingQr -> {
        val qrSizeDp = 150.dp
        val sizePx = with(LocalDensity.current) { qrSizeDp.roundToPx() }
        val qrBitmap = remember(s.url, sizePx) { QrCode.bitmap(s.url, sizePx) }
        Box(
          modifier = Modifier
            .size(qrSizeDp + 8.dp)
            .background(Color.White),
          contentAlignment = Alignment.Center
        ) {
          Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Pairing QR code",
            modifier = Modifier.size(qrSizeDp)
          )
        }
      }

      is LinkingViewModel.LinkState.Registering -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator()
          Text("Linking…", modifier = Modifier.padding(top = 8.dp))
        }
      }

      is LinkingViewModel.LinkState.Done -> {
        onLinked()
      }

      is LinkingViewModel.LinkState.Error -> {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(16.dp)
        ) {
          Text(
            text = s.message,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error
          )
          Button(onClick = { viewModel.restart() }, modifier = Modifier.padding(top = 12.dp)) {
            Text("Retry")
          }
        }
      }
    }
  }
}

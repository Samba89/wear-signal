package dev.sam.wearsignal.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.contacts.ContactDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a Signal contact to start a new conversation with. The list is the cached CDSI directory;
 * "Refresh contacts" re-reads the watch's address book and re-runs discovery (rate-limited, so
 * it's manual). Tapping a contact hands back the recipient to open the Wear text input.
 */
@Composable
fun ComposeScreen(onPick: (ContactDirectory.Entry) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var entries by remember { mutableStateOf(emptyList<ContactDirectory.Entry>()) }
  var status by remember { mutableStateOf<String?>(null) }
  var refreshing by remember { mutableStateOf(false) }

  fun runRefresh() {
    if (refreshing) return
    refreshing = true
    status = "Looking up contacts…"
    scope.launch {
      val result = withContext(Dispatchers.IO) { ContactDirectory.refresh(context) }
      entries = withContext(Dispatchers.IO) { ContactDirectory.all() }
      status = when (result) {
        is ContactDirectory.RefreshResult.Success -> when {
          result.lookedUp == 0 -> "No contacts found on watch"
          result.registered == 0 -> "Read ${result.lookedUp} numbers, none on Signal"
          else -> "${result.registered} of ${result.lookedUp} on Signal"
        }
        is ContactDirectory.RefreshResult.RateLimited ->
          "Rate limited — retry in ${result.retryAfterSeconds}s"
        is ContactDirectory.RefreshResult.Failure -> result.message
      }
      refreshing = false
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) runRefresh() else status = "Contacts permission needed"
  }

  fun refreshOrRequestPermission() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
      runRefresh()
    } else {
      permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }
  }

  LaunchedEffect(Unit) {
    entries = withContext(Dispatchers.IO) { ContactDirectory.all() }
    if (entries.isEmpty()) refreshOrRequestPermission()
  }

  ScalingLazyColumn {
    item {
      Text(
        text = "New message",
        style = MaterialTheme.typography.title3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
      )
    }
    item {
      Chip(
        label = { Text(if (refreshing) "Refreshing…" else "Refresh contacts") },
        onClick = { refreshOrRequestPermission() },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    status?.let { message ->
      item {
        Text(
          text = message,
          style = MaterialTheme.typography.caption2,
          color = MaterialTheme.colors.secondary,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
      }
    }
    items(entries.size) { i ->
      val entry = entries[i]
      Chip(
        label = { Text(entry.name) },
        secondaryLabel = { Text(entry.e164) },
        onClick = { onPick(entry) },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

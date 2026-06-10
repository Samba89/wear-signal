package dev.sam.wearsignal.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.AppDeps
import java.text.DateFormat
import java.util.Date

/**
 * Linked-account status and actions.
 */
@Composable
fun StatusScreen(
  onPollNow: () -> Unit,
  onOpenMessages: () -> Unit
) {
  val account = AppDeps.account

  ScalingLazyColumn {
    item {
      Text(
        text = account.e164 ?: "Not linked",
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
      )
    }
    item {
      val last = account.lastPollAt
      Text(
        text = if (last == 0L) "Never polled" else "Last poll: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last))}",
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
      )
    }
    item {
      Chip(
        label = { Text("Poll now") },
        onClick = onPollNow,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    item {
      Chip(
        label = { Text("Messages") },
        onClick = onOpenMessages,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

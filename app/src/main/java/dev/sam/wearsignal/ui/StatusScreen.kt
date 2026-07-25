package dev.sam.wearsignal.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.poll.PollScheduler
import java.text.DateFormat
import java.util.Date

/**
 * Settings: linked-account info, the manual notification control, and debug helpers.
 */
@Composable
fun StatusScreen() {
  val context = LocalContext.current
  val account = AppDeps.account
  var intervalMinutes by remember { mutableIntStateOf(account.pollIntervalMinutes) }
  var backgroundPolling by remember { mutableStateOf(account.backgroundPollingEnabled) }
  var readReceipts by remember { mutableStateOf(account.sendReadReceipts) }
  var override by remember { mutableStateOf(account.phoneConnectedOverride) }

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
      // Manual notification control: on = watch polls and notifies (phone off/away);
      // off = rely on the phone forwarding its notifications.
      Chip(
        label = { Text(if (backgroundPolling) "Notifications: on" else "Notifications: off") },
        secondaryLabel = { Text(if (backgroundPolling) "Polls every $intervalMinutes min" else "Phone forwards only") },
        onClick = {
          backgroundPolling = !backgroundPolling
          account.backgroundPollingEnabled = backgroundPolling
          PollScheduler.scheduleNext(context) // arms or cancels per the flag
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    if (backgroundPolling) {
      item {
        Chip(
          label = { Text("Interval: $intervalMinutes min") },
          onClick = {
            intervalMinutes = when (intervalMinutes) {
              1 -> 5
              5 -> 15
              else -> 1
            }
            account.pollIntervalMinutes = intervalMinutes
            PollScheduler.scheduleNext(context)
          },
          colors = ChipDefaults.secondaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    item {
      // Off by default: the watch can't see the account's read-receipts privacy setting,
      // so it stays silent to senders until the user opts in. Reads always sync to the phone.
      Chip(
        label = { Text(if (readReceipts) "Read receipts: on" else "Read receipts: off") },
        secondaryLabel = { Text(if (readReceipts) "Senders see when you read" else "Only your phone syncs") },
        onClick = {
          readReceipts = !readReceipts
          account.sendReadReceipts = readReceipts
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
    item {
      // Debug helper while testing on the emulator: force the phone-connected state.
      Chip(
        label = {
          Text(
            when (override) {
              null -> "Phone: auto"
              true -> "Phone: connected"
              false -> "Phone: away"
            }
          )
        },
        onClick = {
          override = when (override) {
            null -> true
            true -> false
            false -> null
          }
          account.phoneConnectedOverride = override
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

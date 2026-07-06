package dev.sam.wearsignal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.messages.MessageRow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val INCOMING_BUBBLE = Color(0xFF2C2C2E)

/**
 * One conversation as a chat: own messages right in the accent colour, incoming left;
 * group messages carry the sender's avatar and colour. Opens scrolled to the latest.
 */
@Composable
fun ThreadScreen(
  title: String,
  isGroup: Boolean,
  messages: List<MessageRow>,
  onReply: () -> Unit
) {
  // items: title + messages + reply chip; messages load asynchronously,
  // so scroll to the latest when they arrive rather than at creation
  val listState = rememberScalingLazyListState()
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.scrollToItem(messages.size + 1)
    }
  }

  ScalingLazyColumn(state = listState) {
    item {
      Text(
        text = if (isGroup) "$title 👥" else title,
        style = MaterialTheme.typography.title3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
      )
    }

    items(messages.size) { i ->
      val message = messages[i]
      // Collapse repeated sender chrome when the same person sends several in a row.
      val firstOfRun = i == 0 || messages[i - 1].senderAci != message.senderAci
      Column {
        if (i == 0 || !sameDay(messages[i - 1].sentAt, message.sentAt)) {
          DayDivider(message.sentAt)
        }
        MessageBubble(message = message, isGroup = isGroup, showSender = firstOfRun)
      }
    }

    item {
      Chip(
        label = { Text("Reply") },
        onClick = onReply,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
      )
    }
  }
}

private fun sameDay(a: Long, b: Long): Boolean {
  val calA = Calendar.getInstance().apply { timeInMillis = a }
  val calB = Calendar.getInstance().apply { timeInMillis = b }
  return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
    calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayDivider(at: Long) {
  Text(
    text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at)),
    style = MaterialTheme.typography.caption3,
    color = Color(0xFF9E9E9E),
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
  )
}

@Composable
private fun MessageBubble(message: MessageRow, isGroup: Boolean, showSender: Boolean) {
  val fromSelf = message.fromSelf
  val bubbleShape = if (fromSelf) {
    RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
  } else {
    RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
  }

  Box(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
    Row(
      verticalAlignment = Alignment.Bottom,
      modifier = Modifier
        .align(if (fromSelf) Alignment.CenterEnd else Alignment.CenterStart)
        .widthIn(max = 150.dp)
    ) {
      if (isGroup && !fromSelf) {
        if (showSender) {
          Avatar(name = message.sender, colorKey = message.senderAci, avatarKey = message.senderAci, size = 20.dp)
        } else {
          Box(modifier = Modifier.widthIn(min = 20.dp)) {} // keep bubbles of a run aligned
        }
      }

      Column(modifier = Modifier.padding(start = if (isGroup && !fromSelf) 4.dp else 0.dp)) {
        if (isGroup && !fromSelf && showSender) {
          Text(
            text = message.sender,
            style = MaterialTheme.typography.caption3,
            color = senderColor(message.senderAci),
            modifier = Modifier.padding(start = 6.dp, bottom = 1.dp)
          )
        }
        Column(
          horizontalAlignment = Alignment.End,
          modifier = Modifier
            .clip(bubbleShape)
            .background(if (fromSelf) MaterialTheme.colors.primary else INCOMING_BUBBLE)
            .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
          val contentColor = if (fromSelf) MaterialTheme.colors.onPrimary else Color.White
          Text(
            text = message.body,
            style = MaterialTheme.typography.body2,
            color = contentColor,
            modifier = Modifier.align(Alignment.Start)
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAt)),
              style = MaterialTheme.typography.caption3,
              color = contentColor.copy(alpha = 0.55f)
            )
            if (fromSelf) {
              Text(
                text = if (message.delivered || message.read) " ✓✓" else " ✓",
                style = MaterialTheme.typography.caption3,
                // Read receipts brighten to full strength; sent/delivered stay dimmed.
                color = if (message.read) contentColor else contentColor.copy(alpha = 0.55f)
              )
            }
          }
        }
      }
    }
  }
}

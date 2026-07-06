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
      MessageBubble(message = message, isGroup = isGroup, showSender = firstOfRun)
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
        Box(
          modifier = Modifier
            .clip(bubbleShape)
            .background(if (fromSelf) MaterialTheme.colors.primary else INCOMING_BUBBLE)
            .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
          Text(
            text = message.body,
            style = MaterialTheme.typography.body2,
            color = if (fromSelf) MaterialTheme.colors.onPrimary else Color.White
          )
        }
      }
    }
  }
}

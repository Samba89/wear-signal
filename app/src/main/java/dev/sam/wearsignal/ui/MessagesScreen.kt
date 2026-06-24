package dev.sam.wearsignal.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

data class MessageRow(
  val sender: String,
  val senderAci: String,
  val body: String,
  val sentAt: Long,
  val fromSelf: Boolean,
  val isGroup: Boolean
) {
  /** 1:1 replies only: can't reply to our own messages or to groups. */
  val canReply: Boolean get() = !fromSelf && !isGroup
}

/**
 * Recent decrypted messages, newest first. Tapping a 1:1 (non-self) message opens a reply.
 */
@Composable
fun MessagesScreen(messages: List<MessageRow>, onReply: (MessageRow) -> Unit) {
  if (messages.isEmpty()) {
    ScalingLazyColumn {
      item {
        Text(
          text = "No messages yet",
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
        )
      }
    }
    return
  }

  ScalingLazyColumn {
    items(messages.size) { i ->
      val message = messages[i]
      Card(onClick = { if (message.canReply) onReply(message) }) {
        Text(
          text = message.sender,
          style = MaterialTheme.typography.caption1,
          color = MaterialTheme.colors.primary
        )
        Text(
          text = message.body,
          style = MaterialTheme.typography.body2,
          modifier = Modifier.padding(top = 2.dp)
        )
        if (message.canReply) {
          Text(
            text = "Tap to reply",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.secondary,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }
    }
  }
}

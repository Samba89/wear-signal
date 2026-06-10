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
  val body: String,
  val sentAt: Long,
  val fromSelf: Boolean
)

/**
 * Recent decrypted messages, newest first.
 */
@Composable
fun MessagesScreen(messages: List<MessageRow>) {
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
      Card(onClick = {}) {
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
      }
    }
  }
}

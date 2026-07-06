package dev.sam.wearsignal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.messages.ConversationRow

/**
 * Home screen: poll button, the most recent conversations (paged by "Load more"),
 * then the secondary actions.
 */
@Composable
fun ConversationsScreen(
  conversations: List<ConversationRow>,
  hasMore: Boolean,
  polling: Boolean,
  onPoll: () -> Unit,
  onLoadMore: () -> Unit,
  onOpen: (ConversationRow) -> Unit,
  onNewMessage: () -> Unit,
  onOpenSettings: () -> Unit
) {
  ScalingLazyColumn {
    item {
      Chip(
        label = { Text(if (polling) "Checking…" else "Check for messages") },
        onClick = onPoll,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }

    if (conversations.isEmpty()) {
      item {
        Text(
          text = "No conversations yet",
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        )
      }
    }

    items(conversations.size) { i ->
      val conversation = conversations[i]
      Card(onClick = { onOpen(conversation) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Avatar(
            name = conversation.title,
            colorKey = conversation.peer,
            avatarKey = conversation.peer,
            size = 32.dp
          )
          Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
              text = if (conversation.isGroup) "${conversation.title} 👥" else conversation.title,
              style = MaterialTheme.typography.caption1,
              color = MaterialTheme.colors.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = if (conversation.isGroup || conversation.lastFromSelf) {
                "${conversation.lastSender}: ${conversation.lastBody}"
              } else {
                conversation.lastBody
              },
              style = MaterialTheme.typography.body2,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    if (hasMore) {
      item {
        Chip(
          label = { Text("Load more") },
          onClick = onLoadMore,
          colors = ChipDefaults.secondaryChipColors(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    item {
      Chip(
        label = { Text("New message") },
        onClick = onNewMessage,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
      )
    }
    item {
      Chip(
        label = { Text("Settings") },
        onClick = onOpenSettings,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

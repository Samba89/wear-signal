package dev.sam.wearsignal.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.messages.MessageRow
import dev.sam.wearsignal.messages.attachmentPlaceholder
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

/**
 * Signal-style receipt state: one hollow circle = sent, two hollow = delivered,
 * two filled = read.
 */
@Composable
private fun ReceiptCircles(delivered: Boolean, read: Boolean, color: Color) {
  val two = delivered || read
  val dotColor = if (read) color else color.copy(alpha = 0.65f)
  Canvas(modifier = Modifier.size(width = if (two) 14.dp else 7.dp, height = 7.dp)) {
    val radius = size.height / 2f
    val stroke = 1.2.dp.toPx()
    fun circle(centerX: Float) {
      if (read) {
        drawCircle(dotColor, radius, Offset(centerX, radius))
      } else {
        drawCircle(dotColor, radius - stroke / 2f, Offset(centerX, radius), style = Stroke(stroke))
      }
    }
    circle(radius)
    if (two) {
      circle(size.width - radius)
    }
  }
}

/** The image when downloaded, otherwise a placeholder ("📷 Photo…" until the next poll fetches it). */
@Composable
private fun AttachmentContent(message: MessageRow, contentColor: Color, modifier: Modifier) {
  val path = message.attachmentPath
  val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }

  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = "Photo",
      modifier = modifier
        .padding(bottom = if (message.body.isNotEmpty()) 3.dp else 0.dp)
        .fillMaxWidth()
        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
        .clip(RoundedCornerShape(10.dp))
    )
  } else {
    Text(
      text = attachmentPlaceholder(message.attachmentType) + if (message.attachmentType?.startsWith("image/") == true) "…" else "",
      style = MaterialTheme.typography.body2,
      color = contentColor.copy(alpha = 0.7f),
      modifier = modifier
    )
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
          if (message.attachmentType != null) {
            AttachmentContent(message, contentColor, modifier = Modifier.align(Alignment.Start))
          }
          if (message.body.isNotEmpty()) {
            Text(
              text = message.body,
              style = MaterialTheme.typography.body2,
              color = contentColor,
              modifier = Modifier.align(Alignment.Start)
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAt)),
              style = MaterialTheme.typography.caption3,
              color = contentColor.copy(alpha = 0.55f)
            )
            if (fromSelf) {
              Spacer(modifier = Modifier.width(3.dp))
              ReceiptCircles(delivered = message.delivered, read = message.read, color = contentColor)
            }
          }
        }
      }
    }
  }
}

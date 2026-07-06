package dev.sam.wearsignal.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import dev.sam.wearsignal.AppDeps
import kotlin.math.abs

/** Deterministic per-sender palette (Signal-ish avatar colours, readable with white text). */
private val AVATAR_COLORS = listOf(
  Color(0xFFD32F2F), // red
  Color(0xFFC2185B), // pink
  Color(0xFF7B1FA2), // purple
  Color(0xFF512DA8), // deep purple
  Color(0xFF1976D2), // blue
  Color(0xFF0288D1), // light blue
  Color(0xFF00796B), // teal
  Color(0xFF388E3C), // green
  Color(0xFFF57C00), // orange
  Color(0xFFE64A19), // deep orange
  Color(0xFF5D4037), // brown
  Color(0xFF455A64)  // blue grey
)

/** Stable colour for a sender/conversation key (ACI or group id). */
fun senderColor(key: String): Color = AVATAR_COLORS[abs(key.hashCode()) % AVATAR_COLORS.size]

private fun initials(name: String): String {
  val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
  return when {
    words.isEmpty() -> "?"
    words.size == 1 -> words[0].take(1).uppercase()
    else -> (words[0].take(1) + words[1].take(1)).uppercase()
  }
}

/**
 * Round avatar: the cached photo for [avatarKey] (contact ACI or group id) if there is
 * one, otherwise a monogram of [name] on the sender colour of [colorKey].
 */
@Composable
fun Avatar(name: String, colorKey: String, avatarKey: String?, size: Dp) {
  // Keyed on the file timestamp so an avatar fetched by the last poll shows up on recomposition.
  val file = avatarKey?.let { AppDeps.avatars.fileFor(it) }
  val bitmap: ImageBitmap? = remember(file?.path, file?.lastModified()) {
    file?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
  }

  if (bitmap != null) {
    Image(
      bitmap = bitmap,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(size).clip(CircleShape)
    )
  } else {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(size).clip(CircleShape).background(senderColor(colorKey))
    ) {
      Text(
        text = initials(name),
        color = Color.White,
        fontSize = (size.value * 0.42f).sp
      )
    }
  }
}

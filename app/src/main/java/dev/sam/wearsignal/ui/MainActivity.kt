package dev.sam.wearsignal.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.link.LinkingViewModel
import dev.sam.wearsignal.messages.ConversationRow
import dev.sam.wearsignal.messages.MessageRow
import dev.sam.wearsignal.messages.MessageSender
import dev.sam.wearsignal.poll.MaintenanceWorker
import dev.sam.wearsignal.poll.PollScheduler
import dev.sam.wearsignal.poll.Poller
import dev.sam.wearsignal.tile.Glanceables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    setContent {
      MaterialTheme {
        WearSignalNavHost()
      }
    }
  }
}

@Composable
fun WearSignalNavHost() {
  val navController: NavHostController = rememberSwipeDismissableNavController()
  val startDestination = if (AppDeps.account.isLinked) "conversations" else "pairing"
  val scope = rememberCoroutineScope()

  // Selected conversation is held here instead of a route argument: peers are
  // base64 group ids ('/', '=') that don't survive route parsing.
  var openConversation by remember { mutableStateOf<ConversationRow?>(null) }
  var polling by remember { mutableStateOf(false) }
  var pollCount by remember { mutableIntStateOf(0) } // bumped after each poll so screens reload
  var pollStatus by remember { mutableStateOf<String?>(null) } // error text when the last poll failed

  fun pollNow() {
    if (polling) return
    polling = true
    scope.launch {
      // Silent: the user is looking at the app, so notifying for what they're reading is noise.
      val result = withContext(Dispatchers.IO) { Poller.poll(silent = true) }
      polling = false
      pollCount++
      pollStatus = when (result) {
        is Poller.Result.Failure -> result.message
        is Poller.Result.Success -> null
      }
    }
  }

  SwipeDismissableNavHost(navController = navController, startDestination = startDestination) {
    composable("pairing") {
      val viewModel: LinkingViewModel = viewModel()
      val context = androidx.compose.ui.platform.LocalContext.current
      PairingScreen(viewModel = viewModel) {
        PollScheduler.scheduleNext(context)
        MaintenanceWorker.ensureScheduled(context)
        navController.navigate("conversations") {
          popUpTo("pairing") { inclusive = true }
        }
      }
    }
    composable("settings") {
      StatusScreen()
    }
    composable("conversations") {
      var limit by remember { mutableIntStateOf(10) }
      var conversations by remember { mutableStateOf(listOf<ConversationRow>()) }
      LaunchedEffect(pollCount, limit) {
        // one extra row tells us whether "Load more" has anything to load
        conversations = withContext(Dispatchers.IO) { AppDeps.messages.conversations(limit + 1) }
      }
      ConversationsScreen(
        conversations = conversations.take(limit),
        hasMore = conversations.size > limit,
        polling = polling,
        pollStatus = pollStatus,
        onPoll = { pollNow() },
        onLoadMore = { limit += 10 },
        onOpen = { conversation ->
          openConversation = conversation
          navController.navigate("thread")
        },
        onNewMessage = { navController.navigate("compose") },
        onOpenSettings = { navController.navigate("settings") }
      )
    }
    composable("thread") {
      val conversation = openConversation
      if (conversation == null) {
        navController.popBackStack()
        return@composable
      }
      var messages by remember { mutableStateOf(listOf<MessageRow>()) }
      var refreshKey by remember { mutableIntStateOf(0) }
      val context = LocalContext.current
      LaunchedEffect(refreshKey, pollCount) {
        val newlySeen = withContext(Dispatchers.IO) {
          messages = AppDeps.messages.thread(conversation.peer, AppDeps.account.aci?.toString())
          // Viewing the thread counts as reading: clear these from the tile/complication unread count.
          AppDeps.messages.markThreadSeen(conversation.peer)
        }
        if (newlySeen > 0) {
          Glanceables.requestUpdate(context)
        }
      }
      val send = rememberSendLauncher(onSent = { refreshKey++ })
      ThreadScreen(
        title = conversation.title,
        isGroup = conversation.isGroup,
        messages = messages,
        polling = polling,
        pollStatus = pollStatus,
        onPoll = { pollNow() },
        onReply = { send(conversation.peer, conversation.isGroup, conversation.title) },
        onReact = { message, emoji ->
          scope.launch {
            withContext(Dispatchers.IO) {
              // Picking your current reaction again retracts it.
              val remove = message.reactions.any { it.mine && it.emoji == emoji }
              MessageSender.sendReaction(
                peer = conversation.peer,
                isGroup = conversation.isGroup,
                targetAuthorAci = message.senderAci,
                targetSentAt = message.sentAt,
                emoji = emoji,
                remove = remove
              )
            }
            refreshKey++
          }
        }
      )
    }
    composable("compose") {
      val send = rememberSendLauncher(onSent = { navController.popBackStack() })
      ComposeScreen(onPick = { entry -> send(entry.serviceId, false, entry.name) })
    }
  }
}

/**
 * Hosts the Wear OS native text-input (voice/keyboard/canned) and returns a callback that, given a
 * conversation peer (ServiceId or group id) and a display label, opens the input and sends the
 * typed text. Used for replies in a thread and for starting a new conversation.
 */
@Composable
fun rememberSendLauncher(onSent: () -> Unit): (peer: String, isGroup: Boolean, label: String) -> Unit {
  val scope = rememberCoroutineScope()
  val pending = remember { mutableStateOf<Pair<String, Boolean>?>(null) }

  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val target = pending.value
    pending.value = null
    val text = result.data
      ?.let { android.app.RemoteInput.getResultsFromIntent(it) }
      ?.getCharSequence(REPLY_INPUT_KEY)
      ?.toString()
      ?.trim()
    if (result.resultCode == android.app.Activity.RESULT_OK && target != null && !text.isNullOrEmpty()) {
      scope.launch {
        withContext(Dispatchers.IO) { MessageSender.send(target.first, target.second, text) }
        onSent()
      }
    }
  }

  return { peer, isGroup, label ->
    pending.value = peer to isGroup
    val remoteInput = android.app.RemoteInput.Builder(REPLY_INPUT_KEY)
      .setLabel("Message $label")
      .build()
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    launcher.launch(intent)
  }
}

private const val REPLY_INPUT_KEY = "reply_text"

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.link.LinkingViewModel
import dev.sam.wearsignal.messages.MessageSender
import dev.sam.wearsignal.poll.PollScheduler
import dev.sam.wearsignal.poll.Poller
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
  val startDestination = if (AppDeps.account.isLinked) "status" else "pairing"
  val scope = rememberCoroutineScope()

  SwipeDismissableNavHost(navController = navController, startDestination = startDestination) {
    composable("pairing") {
      val viewModel: LinkingViewModel = viewModel()
      val context = androidx.compose.ui.platform.LocalContext.current
      PairingScreen(viewModel = viewModel) {
        PollScheduler.scheduleNext(context)
        navController.navigate("status") {
          popUpTo("pairing") { inclusive = true }
        }
      }
    }
    composable("status") {
      var polling by remember { mutableStateOf(false) }
      StatusScreen(
        polling = polling,
        onPollNow = {
          if (!polling) {
            polling = true
            scope.launch {
              withContext(Dispatchers.IO) { Poller.poll() }
              polling = false
            }
          }
        },
        onOpenMessages = { navController.navigate("messages") }
      )
    }
    composable("messages") {
      var messages by remember { mutableStateOf(listOf<MessageRow>()) }
      var refreshKey by remember { mutableIntStateOf(0) }
      LaunchedEffect(refreshKey) {
        messages = withContext(Dispatchers.IO) { AppDeps.messages.recent() }
      }
      val onReply = rememberReplyLauncher(onSent = { refreshKey++ })
      MessagesScreen(messages = messages, onReply = onReply)
    }
  }
}

/**
 * Hosts the Wear OS native text-input (voice/keyboard/canned) and returns a callback that,
 * given a 1:1 message, opens the input and sends the typed text as a reply.
 */
@Composable
fun rememberReplyLauncher(onSent: () -> Unit): (MessageRow) -> Unit {
  val scope = rememberCoroutineScope()
  val pendingAci = remember { mutableStateOf<String?>(null) }

  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    val aci = pendingAci.value
    pendingAci.value = null
    val text = result.data
      ?.let { android.app.RemoteInput.getResultsFromIntent(it) }
      ?.getCharSequence(REPLY_INPUT_KEY)
      ?.toString()
      ?.trim()
    if (result.resultCode == android.app.Activity.RESULT_OK && aci != null && !text.isNullOrEmpty()) {
      scope.launch {
        withContext(Dispatchers.IO) { MessageSender.sendText(aci, text) }
        onSent()
      }
    }
  }

  return { row ->
    pendingAci.value = row.senderAci
    val remoteInput = android.app.RemoteInput.Builder(REPLY_INPUT_KEY)
      .setLabel("Reply to ${row.sender}")
      .build()
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    launcher.launch(intent)
  }
}

private const val REPLY_INPUT_KEY = "reply_text"

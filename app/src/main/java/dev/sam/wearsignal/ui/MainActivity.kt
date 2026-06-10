package dev.sam.wearsignal.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.link.LinkingViewModel
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
      PairingScreen(viewModel = viewModel) {
        navController.navigate("status") {
          popUpTo("pairing") { inclusive = true }
        }
      }
    }
    composable("status") {
      var polling by remember { mutableStateOf(false) }
      StatusScreen(
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
      LaunchedEffect(Unit) {
        messages = withContext(Dispatchers.IO) { AppDeps.messages.recent() }
      }
      MessagesScreen(messages = messages)
    }
  }
}

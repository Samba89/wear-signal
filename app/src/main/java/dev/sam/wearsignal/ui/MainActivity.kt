package dev.sam.wearsignal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.sam.wearsignal.AppDeps
import dev.sam.wearsignal.link.LinkingViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        WearSignalNavHost()
      }
    }
  }
}

@androidx.compose.runtime.Composable
fun WearSignalNavHost() {
  val navController: NavHostController = rememberSwipeDismissableNavController()
  val startDestination = if (AppDeps.account.isLinked) "status" else "pairing"

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
      StatusScreen(
        onPollNow = { /* wired up in the receive increment */ },
        onOpenMessages = { navController.navigate("messages") }
      )
    }
    composable("messages") {
      var messages by remember { mutableStateOf(listOf<MessageRow>()) }
      MessagesScreen(messages = messages)
    }
  }
}

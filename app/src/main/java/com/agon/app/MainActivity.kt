package com.agon.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agon.app.ui.screens.HomeScreen
import com.agon.app.ui.screens.LocalTestScreen
import com.agon.app.ui.screens.LogScreen
import com.agon.app.ui.screens.RoomScreen
import com.agon.app.ui.screens.SettingsScreen
import com.agon.app.ui.theme.AgonAppTheme
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AgonAppTheme {
                MainApp()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().build())
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") { HomeScreen(navController) }
            composable("settings") { SettingsScreen(navController) }
            composable("local_test") { LocalTestScreen() }
            composable("logs") { LogScreen(navController) }
            composable(
                "room/{roomId}/{isHost}?webUrl={webUrl}",
                arguments = listOf(
                    navArgument("roomId") { type = NavType.StringType },
                    navArgument("isHost") { type = NavType.BoolType },
                    navArgument("webUrl") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val roomId = entry.arguments?.getString("roomId") ?: ""
                val isHost = entry.arguments?.getBoolean("isHost") ?: false
                val encodedUrl = entry.arguments?.getString("webUrl")
                val webUrl = encodedUrl?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
                RoomScreen(roomId, isHost, webUrl, navController = navController)
            }
        }
    }
}

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.capture.CaptureScreen
import com.example.ui.navigation.CaptureRoute
import com.example.ui.navigation.SessionsRoute
import com.example.ui.sessions.SessionsScreen
import com.example.ui.theme.PanoGuideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanoGuideApp()
        }
    }
}

@Composable
fun PanoGuideApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = SessionsRoute
    ) {
        composable<SessionsRoute> {
            PanoGuideTheme {
                SessionsScreen(
                    onOpenScene = { sceneId ->
                        navController.navigate(CaptureRoute(sceneId))
                    }
                )
            }
        }
        composable<CaptureRoute> { backStackEntry ->
            val route: CaptureRoute = backStackEntry.toRoute()
            // Capture viewfinder always uses the dark precision instrument palette
            PanoGuideTheme(darkTheme = true) {
                CaptureScreen(
                    sceneId = route.sceneId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

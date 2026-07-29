package com.gios.lightauth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightauth.ui.AuthViewModel
import com.gios.lightauth.ui.CodeScreen
import com.gios.lightauth.ui.ConfirmRemoveScreen
import com.gios.lightauth.ui.HomeScreen
import com.gios.lightauth.ui.ScanScreen
import com.gios.lightauth.ui.theme.LightAuthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Codes are meant to be read off the screen and typed elsewhere, not captured.
        // FLAG_SECURE keeps them out of screenshots and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            LightAuthTheme {
                val nav = rememberNavController()
                val vm: AuthViewModel = viewModel()

                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = vm,
                            onAddNew = { nav.navigate("scan") },
                            onOpenAccount = { id -> nav.navigate("code/$id") },
                        )
                    }
                    composable("scan") {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onScanned = { raw ->
                                // Leave the scanner first. A bad QR surfaces as the error
                                // dialog on home, and that dialog cannot show over a
                                // screen that is about to be popped.
                                nav.popBackStack("home", inclusive = false)
                                vm.addFromQr(raw) { id -> nav.navigate("code/$id") }
                            },
                        )
                    }
                    composable(
                        "code/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments!!.getLong("id")
                        CodeScreen(
                            vm = vm,
                            accountId = id,
                            onBack = { nav.popBackStack() },
                            onRemove = { nav.navigate("confirm-remove/$id") },
                        )
                    }
                    composable(
                        "confirm-remove/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        val id = entry.arguments!!.getLong("id")
                        ConfirmRemoveScreen(
                            vm = vm,
                            accountId = id,
                            onCancel = { nav.popBackStack() },
                            // The code screen is now showing a deleted account, so drop
                            // both it and the confirm screen off the back stack.
                            onRemoved = { nav.popBackStack("home", inclusive = false) },
                        )
                    }
                }
            }
        }
    }
}

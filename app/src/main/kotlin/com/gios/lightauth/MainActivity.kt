package com.gios.lightauth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.gios.lightauth.ui.theme.LightAuthTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

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

                val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
                    val raw = result.contents?.trim() ?: return@rememberLauncherForActivityResult
                    vm.addFromQr(raw) { id ->
                        nav.navigate("code/$id")
                    }
                }

                fun addNew() {
                    scanQr.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setBeepEnabled(false)
                            .setPrompt("Scan the 2FA QR code"),
                    )
                }

                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = vm,
                            onAddNew = ::addNew,
                            onOpenAccount = { id -> nav.navigate("code/$id") },
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

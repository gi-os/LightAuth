package com.gios.lightauth

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightauth.hw.LightKey
import com.gios.lightauth.hw.LightKeys
import com.gios.lightauth.hw.LocalWheelBus
import com.gios.lightauth.hw.WheelBus
import com.gios.lightauth.time.TimeSource
import com.gios.lightauth.ui.AuthViewModel
import com.gios.lightauth.ui.ClockScreen
import com.gios.lightauth.ui.CodeScreen
import com.gios.lightauth.ui.ConfirmRemoveScreen
import com.gios.lightauth.ui.HomeScreen
import com.gios.lightauth.ui.ScanScreen
import com.gios.lightauth.ui.theme.LightAuthTheme

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — so the wheel is read before anything on screen can
     * claim it. Both halves of a notch are consumed: one notch is a complete DOWN+UP pair,
     * and letting the UP through would land in whatever has focus as a keypress.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Before any code screen can compose: the correction has to be loaded from prefs
        // or the first code shown after a cold start is the uncorrected one.
        TimeSource.init(this)

        // Codes are meant to be read off the screen and typed elsewhere, not captured.
        // FLAG_SECURE keeps them out of screenshots and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            LightAuthTheme {
                val nav = rememberNavController()
                val vm: AuthViewModel = viewModel()

                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                vm = vm,
                                onAddNew = { nav.navigate("scan") },
                                onOpenAccount = { id -> nav.navigate("code/$id") },
                                onOpenClock = { nav.navigate("clock") },
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
                                onOpenClock = { nav.navigate("clock") },
                            )
                        }
                        composable("clock") {
                            ClockScreen(onBack = { nav.popBackStack() })
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
}

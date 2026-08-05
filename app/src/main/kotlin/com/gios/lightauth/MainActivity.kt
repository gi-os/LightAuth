package com.gios.lightauth

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gios.lightauth.backup.SnapshotStore
import com.gios.lightauth.data.TotpAccountRepository
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
import com.gios.lightauth.ui.LockScreen
import com.gios.lightauth.ui.PinSetupScreen
import com.gios.lightauth.ui.ScanScreen
import com.gios.lightauth.ui.SettingsScreen
import com.gios.lightauth.ui.theme.LightAuthTheme
import com.gios.lightauth.vault.Vault
import com.gios.lightauth.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // And before anything asks for a secret: this decides whether the app opens on the
        // account list or on the keypad.
        Vault.init(this)

        // One-time re-wrap of pre-vault rows onto the vault key. Only possible while the
        // vault is open, which on the first launch after upgrading it always is — no PIN can
        // exist yet.
        if (Vault.key() != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    TotpAccountRepository.getInstance(applicationContext).migrateToVault()
                }
                // No PIN, so nothing to type: apply any restore waiting from LightSync and
                // write the snapshot the next backup will ship.
                runCatching { SnapshotStore.ingestPending(applicationContext, null) }
                runCatching { SnapshotStore.refresh(applicationContext) }
            }
        }

        // Codes are meant to be read off the screen and typed elsewhere, not captured.
        // FLAG_SECURE keeps them out of screenshots and the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        setContent {
            LightAuthTheme {
                val nav = rememberNavController()
                val vm: AuthViewModel = viewModel()
                val vaultState by Vault.state.collectAsStateWithLifecycle()

                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    if (vaultState == VaultState.Locked) {
                        // Not a route, deliberately. A locked vault must not be a place the
                        // back stack can navigate away from, and every screen in the graph
                        // below assumes a readable database.
                        LockScreen(onUnlocked = { pin -> vm.onVaultOpened(pin) })
                    } else {
                        NavHost(nav, startDestination = "home") {
                            composable("home") {
                                HomeScreen(
                                    vm = vm,
                                    onAddNew = { nav.navigate("scan") },
                                    onOpenAccount = { id -> nav.navigate("code/$id") },
                                    onOpenSettings = { nav.navigate("settings") },
                                )
                            }
                            composable("scan") {
                                ScanScreen(
                                    onBack = { nav.popBackStack() },
                                    onScanned = { raw ->
                                        // Leave the scanner first. A bad QR surfaces as the
                                        // error dialog on home, and that dialog cannot show
                                        // over a screen that is about to be popped.
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
                            composable("settings") {
                                SettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    onSetPin = { nav.navigate("pin/new") },
                                    onChangePin = { nav.navigate("pin/change") },
                                    onOpenClock = { nav.navigate("clock") },
                                )
                            }
                            composable(
                                "pin/{mode}",
                                arguments = listOf(
                                    navArgument("mode") { type = NavType.StringType },
                                ),
                            ) { entry ->
                                PinSetupScreen(
                                    changing = entry.arguments?.getString("mode") == "change",
                                    onDone = { nav.popBackStack("settings", inclusive = false) },
                                    onCancel = { nav.popBackStack() },
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
                                    // The code screen is now showing a deleted account, so
                                    // drop both it and the confirm screen off the back stack.
                                    onRemoved = { nav.popBackStack("home", inclusive = false) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The key leaves memory the moment the app stops being the thing on screen.
     *
     * `onStop` rather than `onPause`: `onPause` also fires for the camera permission dialog
     * the scanner puts up, and re-locking mid-enrolment would throw away the QR just read.
     * `onStop` is the screen going off, the app going to recents, or another app coming
     * forward — which is exactly the set of moments the phone might change hands.
     *
     * Nothing needs re-locking when no PIN is set: there would be nothing to re-enter, and
     * [Vault.lock] leaves that case alone on purpose.
     */
    override fun onStop() {
        super.onStop()
        Vault.lock()
    }
}

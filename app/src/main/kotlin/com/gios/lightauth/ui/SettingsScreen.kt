package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.lightauth.hw.WheelScroll
import com.gios.lightauth.ui.theme.Faint
import com.gios.lightauth.vault.Vault

/**
 * Settings: the PIN, the erase-on-failure option, and a way through to the clock.
 *
 * Reaching this screen at all requires the vault to be open — either no PIN is set, or the
 * PIN has already been entered. That is what lets every action here work without asking
 * again, except changing the PIN, which genuinely needs the old one to re-wrap the key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSetPin: () -> Unit,
    onChangePin: () -> Unit,
    onOpenClock: () -> Unit,
) {
    // Read once per visit and bumped by hand, since the vault's prefs are not observable and
    // this screen is the only thing that changes them.
    var revision by remember { mutableIntStateOf(0) }
    val pinSet = remember(revision) { Vault.isPinSet() }
    val wipeOn = remember(revision) { Vault.isWipeEnabled() }
    val wipeAfter = remember(revision) { Vault.wipeAfter() }

    var confirmDisable by remember { mutableStateOf(false) }
    var confirmWipeOn by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    WheelScroll(scroll)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(scroll),
        ) {
            SectionLabel("Lock")

            ToggleRow(
                label = "Require a PIN",
                sub = if (pinSet) "Asked for every time you open the app" else "Off",
                checked = pinSet,
                onToggle = { wanted ->
                    if (wanted) onSetPin() else confirmDisable = true
                },
            )
            Rule()

            if (pinSet) {
                MenuRow(
                    label = "Change PIN",
                    sub = "Asks for the current one first",
                    onClick = onChangePin,
                )
                Rule()
            }

            SectionLabel("If the phone is stolen")

            ToggleRow(
                label = "Erase after failed attempts",
                sub = if (!pinSet) {
                    "Set a PIN first"
                } else if (wipeOn) {
                    "Destroys the key after $wipeAfter wrong PINs"
                } else {
                    "Off — wrong PINs only slow down"
                },
                checked = wipeOn,
                enabled = pinSet,
                onToggle = { wanted ->
                    if (wanted) confirmWipeOn = true else { Vault.setWipeEnabled(false); revision++ }
                },
            )
            Rule()

            if (pinSet && wipeOn) {
                MenuRow(
                    label = "Erase after",
                    sub = "$wipeAfter wrong attempts — tap to change",
                    onClick = {
                        // 5 → 10 → 20 → 5. Three sensible values beat a number picker on a
                        // screen this size.
                        Vault.setWipeAfter(
                            when (wipeAfter) {
                                5 -> 10
                                10 -> 20
                                else -> 5
                            },
                        )
                        revision++
                    },
                )
                Rule()
            }

            Text(
                "Wrong PINs are always slowed down — three free tries, then a delay that " +
                    "climbs to 15 minutes. Erasing is separate and off by default: it " +
                    "destroys the key the accounts are encrypted under, so it cannot be " +
                    "undone from this phone. Only turn it on if your LightSync backup is " +
                    "actually running.",
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SectionLabel("Codes")
            MenuRow(
                label = "Clock",
                sub = "Fix drift if codes are being rejected",
                onClick = onOpenClock,
            )
            Rule()
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDisable) {
        ConfirmDialog(
            message = "Turn off the PIN? The accounts stay encrypted to this phone, but " +
                "anyone holding it can open the app and read your codes.",
            confirmLabel = "TURN OFF",
            onConfirm = {
                Vault.clearPin()
                // Back to plaintext URIs, or the next backup ships a payload sealed with a
                // PIN that no longer exists.
                scope.resealSnapshot(context)
                confirmDisable = false
                revision++
            },
            onDismiss = { confirmDisable = false },
        )
    }

    if (confirmWipeOn) {
        ConfirmDialog(
            message = "Erase the vault after $wipeAfter wrong PINs? This destroys the key, " +
                "so every account goes with it and cannot be recovered from this phone — " +
                "only from a LightSync backup.",
            confirmLabel = "ENABLE",
            onConfirm = {
                Vault.setWipeEnabled(true)
                confirmWipeOn = false
                revision++
            },
            onDismiss = { confirmWipeOn = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141414),
        textContentColor = Color.White,
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.White) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Color.White) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.bodyMedium,
        color = Faint,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

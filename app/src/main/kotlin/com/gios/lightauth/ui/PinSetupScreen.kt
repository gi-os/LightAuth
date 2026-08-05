package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.gios.lightauth.vault.Vault
import com.gios.lightauth.vault.VaultCrypto

/** Which step of setting a PIN we are on. */
private enum class Step { Current, Choose, Confirm }

/**
 * Setting, changing, or confirming a PIN.
 *
 * Changing an existing PIN asks for the old one first. Not for show: [Vault.setPin] re-wraps
 * the same vault key under the new PIN, which needs the key unwrapped, which needs the old
 * PIN. So the prompt is the actual requirement rather than a courtesy check that could be
 * skipped.
 *
 * A new PIN is entered twice. A PIN that only exists in your head and is wrong in the vault
 * locks you out of every account permanently, so the confirmation step is the cheapest
 * insurance in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinSetupScreen(
    changing: Boolean,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(if (changing) Step.Current else Step.Choose) }
    var entered by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun submit() {
        when (step) {
            Step.Current -> {
                if (Vault.verifyPin(entered)) {
                    step = Step.Choose
                    message = null
                } else {
                    message = "That is not your current PIN."
                }
                entered = ""
            }
            Step.Choose -> {
                if (!VaultCrypto.isValidPin(entered)) {
                    message = "Use ${VaultCrypto.MIN_PIN_LENGTH} to " +
                        "${VaultCrypto.MAX_PIN_LENGTH} digits."
                } else {
                    first = entered
                    step = Step.Confirm
                    message = null
                }
                entered = ""
            }
            Step.Confirm -> {
                if (entered == first) {
                    if (Vault.setPin(entered)) {
                        // The snapshot is now sealed with the wrong key — re-seal before the
                        // next backup ships something the new PIN cannot open.
                        scope.resealSnapshot(context)
                        onDone()
                    } else {
                        message = "Could not set the PIN. Try again."
                        step = Step.Choose
                    }
                } else {
                    message = "Those did not match. Start again."
                    step = Step.Choose
                    first = ""
                }
                entered = ""
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text(if (changing) "Change PIN" else "Set PIN") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            KeypadHeading(
                title = when (step) {
                    Step.Current -> "Current PIN"
                    Step.Choose -> "Choose a PIN"
                    Step.Confirm -> "Enter it again"
                },
                subtitle = message ?: when (step) {
                    Step.Current -> "Needed to re-wrap the vault key."
                    Step.Choose -> "${VaultCrypto.MIN_PIN_LENGTH}–" +
                        "${VaultCrypto.MAX_PIN_LENGTH} digits, then ENTER."
                    Step.Confirm -> "Forgetting it means losing every account."
                },
                entered = entered.length,
                // Unknown length while choosing, so show the minimum as a hint of progress.
                total = when (step) {
                    Step.Current -> Vault.pinLength()
                    else -> maxOf(entered.length, VaultCrypto.MIN_PIN_LENGTH)
                },
            )
            Keypad(
                entered = entered,
                maxLength = VaultCrypto.MAX_PIN_LENGTH,
                enabled = true,
                onDigit = { digit ->
                    if (entered.length < VaultCrypto.MAX_PIN_LENGTH) {
                        message = null
                        entered += digit
                        // Only the current-PIN step knows the length, so only it can submit
                        // for you. Choosing needs ENTER, or a 4-digit PIN could never be 5.
                        if (step == Step.Current && entered.length == Vault.pinLength()) submit()
                    }
                },
                onDelete = { entered = entered.dropLast(1) },
                onSubmit = ::submit,
            )
        }
    }
}

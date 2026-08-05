package com.gios.lightauth.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.gios.lightauth.vault.Lockout
import com.gios.lightauth.vault.Vault
import com.gios.lightauth.vault.VaultCrypto
import kotlinx.coroutines.delay

/**
 * The keypad you get before anything else, whenever a PIN is set.
 *
 * Nothing behind it is readable while it is up — this is not a screen guarding a list, it is
 * the list being encrypted. So there is deliberately no way past it: back is swallowed
 * rather than dropping to the home screen, because a back press that exits is a back press
 * that reveals nothing, and a user who thinks it might is a user who taps it in a panic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreen(onUnlocked: (String) -> Unit) {
    var entered by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableIntStateOf(Vault.remainingLockoutSeconds().toInt()) }

    BackHandler(enabled = true) { /* no way past the keypad */ }

    // Ticks only while a lockout is running, so an idle keypad costs nothing.
    LaunchedEffect(waiting) {
        if (waiting <= 0) return@LaunchedEffect
        while (Vault.remainingLockoutSeconds() > 0) {
            waiting = Vault.remainingLockoutSeconds().toInt()
            delay(500)
        }
        waiting = 0
        message = null
    }

    val locked = waiting > 0

    fun submit() {
        when (val result = Vault.unlock(entered)) {
            is Vault.UnlockResult.Success -> {
                val pin = entered
                entered = ""
                onUnlocked(pin)
            }
            is Vault.UnlockResult.Wrong -> {
                entered = ""
                waiting = result.waitSeconds.toInt()
                message = if (result.waitSeconds > 0) {
                    "Wrong PIN. Wait ${Lockout.formatWait(result.waitSeconds)}."
                } else {
                    val left = Lockout.FREE_ATTEMPTS - result.attemptsUsed
                    if (left > 0) "Wrong PIN. $left more before a delay." else "Wrong PIN."
                }
            }
            is Vault.UnlockResult.Throttled -> {
                entered = ""
                waiting = result.waitSeconds.toInt()
                message = "Wait ${Lockout.formatWait(result.waitSeconds)}."
            }
            Vault.UnlockResult.Wiped -> {
                entered = ""
                message = "Too many attempts. The vault has been erased."
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = { TopAppBar(colors = barColors(), title = { Text("Authenticator") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            KeypadHeading(
                title = "Enter PIN",
                subtitle = when {
                    locked -> "Locked for ${Lockout.formatWait(waiting.toLong())}."
                    else -> message
                },
                entered = entered.length,
                total = Vault.pinLength(),
            )
            Keypad(
                entered = entered,
                maxLength = VaultCrypto.MAX_PIN_LENGTH,
                enabled = !locked,
                onDigit = { digit ->
                    if (entered.length < VaultCrypto.MAX_PIN_LENGTH) {
                        message = null
                        entered += digit
                        // Auto-submit once the stored length is reached, so a 4-digit PIN
                        // needs no ENTER. A longer PIN still can, since it stops here too.
                        if (entered.length == Vault.pinLength()) submit()
                    }
                },
                onDelete = { entered = entered.dropLast(1) },
                onSubmit = ::submit,
            )
        }
    }
}

package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightauth.data.StoredAccount
import com.gios.lightauth.time.TimeMath
import com.gios.lightauth.time.TimeSource
import com.gios.lightauth.totp.TotpGenerator
import com.gios.lightauth.totp.formatExpiryCountdown
import com.gios.lightauth.totp.groupCodeDigits
import com.gios.lightauth.ui.theme.Dim
import com.gios.lightauth.ui.theme.Faint
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    vm: AuthViewModel,
    accountId: Long,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    onOpenClock: () -> Unit,
) {
    val account by produceState<StoredAccount?>(null, accountId) { value = vm.loadAccount(accountId) }
    val secret by produceState<String?>(null, accountId) { value = vm.loadSecret(accountId) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Text(
                        account?.issuer?.takeIf { it.isNotBlank() } ?: "Authenticator",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            // CLOCK sits here on purpose: this is the screen you are looking at when a
            // code gets rejected, so the fix for a drifted clock is one tap away from it.
            if (account != null) {
                ActionBar(
                    listOf(
                        BarAction("CLOCK", onOpenClock),
                        BarAction("REMOVE", onRemove),
                    ),
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            val loadedAccount = account
            val loadedSecret = secret
            if (loadedAccount == null || loadedSecret == null) {
                EmptyState("Loading…")
            } else {
                CodeBody(loadedAccount, loadedSecret)
            }
        }
    }
}

@Composable
private fun CodeBody(account: StoredAccount, secret: String) {
    // One tick a second is enough: the code only changes on a period boundary, and the
    // countdown is the only thing that moves in between.
    var now by remember { mutableLongStateOf(TimeSource.nowSeconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = TimeSource.nowSeconds()
            delay(1000)
        }
    }
    val offset = TimeSource.offsetSeconds()

    // A secret that survived the parser but fails here means a corrupt row rather than
    // a bad scan, so say so instead of showing a code that was never valid.
    val totp = remember(now, secret, account) {
        runCatching {
            TotpGenerator.generate(
                secret = secret,
                digits = account.digits,
                period = account.period,
                algorithm = account.algorithm,
                currentUnixTime = now,
            )
        }.getOrNull()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            account.issuer.ifBlank { "Unknown" },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "(${account.label.ifBlank { "—" }})",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            totp?.let { groupCodeDigits(it.code) } ?: "——————",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            totp?.let { "Expires in: ${formatExpiryCountdown(it.remainingSeconds)}" }
                ?: "This account's secret could not be read.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
        )
        // Only when a correction is in force. A code that is right for a time the phone
        // does not believe in is worth saying out loud; "+0s" on every screen is not.
        if (offset != 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Clock corrected ${TimeMath.formatOffset(offset)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
            )
        }
    }
}

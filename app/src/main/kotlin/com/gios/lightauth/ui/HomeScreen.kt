package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.lightauth.hw.WheelScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AuthViewModel,
    onAddNew: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onOpenClock: () -> Unit,
) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    // The account list is the only thing here long enough to scroll, so the wheel drives it.
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(colors = barColors(), title = { Text("Authenticator") })
        },
        bottomBar = {
            ActionBar(
                listOf(
                    BarAction("ADD NEW", onAddNew),
                    BarAction("CLOCK", onOpenClock),
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Color.Black)) {
            if (accounts.isEmpty()) {
                EmptyState("no accounts added\n\nTap ADD NEW and scan\nthe QR code from a site.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(accounts, key = { it.id }) { account ->
                        MenuRow(
                            label = account.issuer.ifBlank { "Unknown" },
                            sub = "(${account.label.ifBlank { "—" }})",
                            onClick = { onOpenAccount(account.id) },
                        )
                    }
                }
            }
        }
    }

    error?.let { MessageDialog(it, onDismiss = vm::dismissError) }
}

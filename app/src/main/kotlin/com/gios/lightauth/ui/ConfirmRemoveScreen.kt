package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightauth.data.StoredAccount
import com.gios.lightauth.ui.theme.Dim

/**
 * The confirm step in front of a delete.
 *
 * Removing an account here is unrecoverable — the secret is sealed with a keystore key
 * and there is no copy anywhere else, so getting the code back means going to the
 * provider and re-enrolling. That is worth a full screen rather than a toast with an
 * undo, especially on a phone where REMOVE sits under a thumb in the action bar.
 *
 * CANCEL is listed first so the destructive action is never where the previous screen's
 * button was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmRemoveScreen(
    vm: AuthViewModel,
    accountId: Long,
    onCancel: () -> Unit,
    onRemoved: () -> Unit,
) {
    val account by produceState<StoredAccount?>(null, accountId) { value = vm.loadAccount(accountId) }
    // Guards the double tap: the delete is asynchronous, and two REMOVEs would fire two
    // navigations.
    var removing by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = {
                    Text(
                        account?.issuer?.takeIf { it.isNotBlank() } ?: "Remove account",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
            )
        },
        bottomBar = {
            ActionBar(
                listOf(
                    BarAction("CANCEL", onCancel),
                    BarAction("REMOVE") {
                        if (!removing) {
                            removing = true
                            vm.remove(accountId, onRemoved)
                        }
                    },
                ),
            )
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize().background(Color.Black).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Are you sure you'd like to remove this account?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val name = account?.displayName
                Text(
                    if (name != null) {
                        "$name will be deleted from this phone. " +
                            "You'll need to set it up again from the site."
                    } else {
                        "You'll need to set it up again from the site."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

package com.gios.lightauth.ui

import android.content.Context
import com.gios.lightauth.backup.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-seal the backup snapshot after the PIN changed.
 *
 * Turning a PIN on has to re-seal it under that PIN, and turning one off has to write it back
 * as plaintext URIs — otherwise the next backup ships a payload nothing on a new phone could
 * open. Easy to forget, so it lives in one named place that both screens call.
 */
fun CoroutineScope.resealSnapshot(context: Context) {
    val app = context.applicationContext
    launch(Dispatchers.IO) { runCatching { SnapshotStore.refresh(app) } }
}

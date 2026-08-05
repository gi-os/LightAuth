package com.gios.lightauth.backup

import android.content.Context
import android.util.Log
import com.gios.lightauth.data.TotpAccountRepository
import com.gios.lightauth.totp.OtpAuthUriParser
import com.gios.lightauth.vault.Vault
import java.io.File

/**
 * The two files that let a locked app still have a backup: the outgoing snapshot and the
 * incoming one waiting to be read.
 *
 * Both sit in `filesDir`, which is inside the app sandbox and inside `allowBackup=false`, so
 * neither is reachable by anything but this app and the provider it exposes to LightSync.
 */
object SnapshotStore {

    private const val TAG = "LightAuthBackup"
    private const val OUTGOING = "backup-snapshot.txt"
    private const val INCOMING = "pending-restore.txt"

    fun outgoing(context: Context) = File(context.filesDir, OUTGOING)

    fun incoming(context: Context) = File(context.filesDir, INCOMING)

    /**
     * Rewrites the outgoing snapshot from the live accounts. Requires an unlocked vault, so
     * every caller is somewhere the user is present.
     *
     * Called after any change to the accounts and once per unlock. Cheap — a dozen short lines
     * and one PBKDF2 — and the alternative is a backup that silently ages.
     */
    fun refresh(context: Context) {
        if (Vault.key() == null) return
        val repo = TotpAccountRepository.getInstance(context)
        val uris = runCatching { repo.exportUris() }.getOrElse {
            Log.w(TAG, "Snapshot refresh failed to read accounts", it)
            return
        }
        // An empty vault writes an empty snapshot on purpose: having removed every account,
        // the truthful backup is an empty one. Only a *locked* read must never be written,
        // and a locked read cannot get this far.
        val sealKey = Vault.backupSealKey()
        val salt = Vault.backupSalt()
        val payload = if (sealKey != null && salt != null) {
            Snapshot.seal(uris, sealKey, salt)
        } else {
            uris.joinToString("\n")
        }
        runCatching { outgoing(context).writeText(payload) }
            .onFailure { Log.w(TAG, "Snapshot write failed", it) }
    }

    fun clearOutgoing(context: Context) {
        runCatching { outgoing(context).delete() }
    }

    /**
     * Imports a payload LightSync restored, if one is waiting.
     *
     * Restore also arrives while locked, so it cannot be applied on the spot; the provider
     * parks the bytes and this runs at the next unlock, when a PIN exists to decrypt with.
     * The file is only deleted once something was actually imported — a restore that arrived
     * sealed under a *previous* PIN survives to be retried after that PIN is entered, rather
     * than being consumed and lost.
     */
    fun ingestPending(context: Context, pin: String?): Int {
        val file = incoming(context)
        if (!file.isFile) return 0
        val payload = runCatching { file.readText() }.getOrNull() ?: return 0

        val uris = if (Snapshot.isSealed(payload)) {
            if (pin == null) return 0
            Snapshot.open(payload, pin) ?: run {
                Log.w(TAG, "Pending restore did not open with this PIN; keeping it for later")
                return 0
            }
        } else {
            payload.lines().map(String::trim).filter { it.startsWith("otpauth://") }
        }

        val repo = TotpAccountRepository.getInstance(context)
        var imported = 0
        uris.forEach { uri ->
            // A single malformed line shouldn't cost you the other twelve accounts.
            OtpAuthUriParser.parse(uri).onSuccess {
                runCatching { repo.addAccount(it) }.onSuccess { imported++ }
            }
        }
        if (imported > 0 || uris.isEmpty()) {
            runCatching { file.delete() }
        }
        Log.i(TAG, "Pending restore: imported $imported of ${uris.size}")
        return imported
    }
}

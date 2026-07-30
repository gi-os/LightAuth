package com.gios.lightauth.backup

import com.gios.lightauth.data.TotpAccountRepository
import com.gios.lightauth.totp.OtpAuthUriParser
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * LightAuth's backup, as `otpauth://` URIs rather than as its database.
 *
 * The database would have been one line of [Contents] and completely useless. Every secret in it
 * is wrapped by an AES key generated inside AndroidKeyStore, which by design cannot be exported —
 * so a copy of `totp_accounts.db` restored onto a new phone is rows of ciphertext with the key
 * missing forever. A backup that looks like a backup and restores into nothing is worse than
 * having none, because you find out at the moment you needed it.
 *
 * So this decrypts on the way out and emits the standard URI form instead: portable, restorable
 * into any authenticator, and readable by hand if it ever comes to that. LightSync seals it before
 * it leaves the phone, so the plaintext exists only in this process and in the pipe to the agent.
 *
 * Restore goes back through the same parser the QR scanner uses. `addAccount` already treats
 * issuer plus label as the identity and updates rather than duplicating, so restoring twice, or
 * onto a phone that still has some of the accounts, converges instead of piling up.
 */
class Backup : LightSyncBackup() {

    override fun label(): String = "Authenticator"

    /** Nothing on disk is portable, so nothing on disk is included. */
    override fun contents() = Contents(restartAfterRestore = false)

    override fun export(out: FileOutputStream) {
        val ctx = context ?: return
        val repo = TotpAccountRepository.getInstance(ctx)
        val lines = repo.exportUris()
        out.writer().use { writer ->
            lines.forEach { writer.write(it + "\n") }
        }
    }

    override fun restore(input: FileInputStream) {
        val ctx = context ?: return
        val repo = TotpAccountRepository.getInstance(ctx)
        input.reader().readLines()
            .map(String::trim)
            .filter { it.startsWith("otpauth://") }
            .forEach { uri ->
                // A single malformed line shouldn't cost you the other twelve accounts.
                OtpAuthUriParser.parse(uri).onSuccess { repo.addAccount(it) }
            }
    }
}

/**
 * The URI form of every stored account, secrets decrypted.
 *
 * Lives here as an extension rather than in the repository because it exists for backup only —
 * the app itself never has a reason to hold a plaintext secret for longer than generating a code.
 */
internal fun TotpAccountRepository.exportUris(): List<String> =
    accountsForBackup().mapNotNull { account ->
        val secret = decryptSecret(account.id) ?: return@mapNotNull null
        val issuer = account.issuer
        val label = account.label
        val path = if (issuer.isNotBlank()) "$issuer:$label" else label
        buildString {
            append("otpauth://totp/").append(enc(path))
            append("?secret=").append(secret)
            if (issuer.isNotBlank()) append("&issuer=").append(enc(issuer))
            append("&algorithm=").append(account.algorithm)
            append("&digits=").append(account.digits)
            append("&period=").append(account.period)
        }
    }

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

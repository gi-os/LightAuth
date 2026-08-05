package com.gios.lightauth.backup

import android.util.Base64
import com.gios.lightauth.vault.VaultCrypto
import javax.crypto.SecretKey

/**
 * The backup payload's on-the-wire form, and the reason the PIN did not quietly kill backups.
 *
 * ## The problem the PIN created
 *
 * LightSync exports in the background, daily, unattended. The vault is locked whenever the app
 * is not in the foreground — which is nearly always, and is the entire point. A locked app
 * cannot read a single secret, so a background export could only ever produce nothing.
 *
 * Producing nothing is the dangerous outcome, not the harmless one: an empty export overwrites
 * a good backup with a file that looks like a backup. So exporting is not attempted live. While
 * the app *is* unlocked it writes a snapshot of the accounts to private storage, and the
 * provider streams that file. The export is then always a file read, which needs no key.
 *
 * ## Why the snapshot is not sealed with the vault key
 *
 * Because it has to survive the phone. The vault key is wrapped by a non-exportable
 * AndroidKeyStore key, so anything sealed with it restores into nothing on a new device — the
 * exact failure the plaintext `otpauth://` export existed to avoid. The snapshot is sealed with
 * a key derived from the **PIN alone**, salt travelling alongside, so a new phone needs only the
 * PIN. That also makes the backup better than it was: it used to leave here as plaintext URIs.
 *
 * With no PIN set there is nothing to derive from, so the payload stays the plaintext URI list
 * it has always been, and LightSync's own encryption remains the only thing protecting it.
 */
object Snapshot {

    private const val MAGIC = "LIGHTAUTH-BACKUP-1"

    /** True if this payload is PIN-sealed rather than plaintext `otpauth://` lines. */
    fun isSealed(payload: String): Boolean = payload.trimStart().startsWith(MAGIC)

    /**
     * `MAGIC / iterations / salt / ciphertext`, one field per line.
     *
     * Iterations are written down rather than assumed: raising [VaultCrypto.ITERATIONS] in a
     * later version must not make every existing backup undecryptable.
     */
    fun seal(uris: List<String>, key: SecretKey, salt: ByteArray): String {
        val body = VaultCrypto.seal(key, uris.joinToString("\n").toByteArray())
        return listOf(
            MAGIC,
            VaultCrypto.ITERATIONS.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(body, Base64.NO_WRAP),
        ).joinToString("\n")
    }

    /** Null on a wrong PIN or a corrupt payload — the two are not worth distinguishing. */
    fun open(payload: String, pin: String): List<String>? {
        val lines = payload.trim().lines()
        if (lines.size < 4 || lines[0].trim() != MAGIC) return null
        val iterations = lines[1].trim().toIntOrNull() ?: return null
        val salt = decode(lines[2]) ?: return null
        val body = decode(lines[3]) ?: return null
        val plaintext = VaultCrypto.open(VaultCrypto.deriveKey(pin, salt, iterations), body)
            ?: return null
        return String(plaintext).lines()
            .map(String::trim)
            .filter { it.startsWith("otpauth://") }
    }

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.decode(value.trim(), Base64.NO_WRAP) }.getOrNull()
}

package com.gios.lightauth.crypto

import com.gios.lightauth.vault.VaultCrypto
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

/**
 * Encrypts one TOTP secret under whatever key it is handed.
 *
 * It used to hold the AndroidKeyStore key itself. It now takes a key per call, because the
 * key that matters is the vault key — which only exists in memory, and only while the vault
 * is unlocked. Making that a parameter rather than a field is what stops a locked app from
 * being able to decrypt anything at all: there is no key for it to reach for.
 *
 * Framing is unchanged, `[12-byte IV | ciphertext+tag]`, so [legacyDecrypt] can still read
 * rows written by the pre-vault versions while the migration runs.
 */
internal object TotpSecretCipher {

    fun encrypt(key: SecretKey, plaintext: String): ByteArray =
        VaultCrypto.seal(key, plaintext.toByteArray(StandardCharsets.UTF_8))

    /** Null on a wrong key or a tampered row, rather than an exception. */
    fun decrypt(key: SecretKey, blob: ByteArray): String? =
        VaultCrypto.open(key, blob)?.toString(StandardCharsets.UTF_8)

    /**
     * Rows written before the vault existed, sealed with the AndroidKeyStore key directly.
     * Used once, by the migration, and never again.
     */
    fun legacyDecrypt(blob: ByteArray): String? =
        VaultCrypto.open(TotpKeystore().getSecretKey(), blob)?.toString(StandardCharsets.UTF_8)
}

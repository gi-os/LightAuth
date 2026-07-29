package com.gios.lightauth.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES key that wraps every stored secret. It is generated inside AndroidKeyStore
 * and is not exportable, so the database file on its own is useless: pulling
 * totp_accounts.db off the phone yields ciphertext and nothing else.
 *
 * No user authentication is required to use the key. LightOS has no biometrics, and
 * gating on the lock screen would mean no codes on a phone with no lock set.
 */
internal class TotpKeystore(
    private val keyAlias: String = KEY_ALIAS,
) {
    fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) return

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: error("TOTP keystore key '$keyAlias' is missing")
        return entry.secretKey
    }

    companion object {
        const val KEY_ALIAS = "totp_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

package com.gios.lightauth.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.gios.lightauth.crypto.TotpSecretCipher
import com.gios.lightauth.totp.Account
import com.gios.lightauth.vault.Vault
import kotlinx.coroutines.flow.Flow

/**
 * Every account, and the only path to a plaintext secret.
 *
 * Since the vault landed, the encryption key is not a field here — it is fetched from
 * [Vault] per operation and is simply absent while locked. So a locked app cannot read a
 * secret even from inside its own process: [decryptSecret] returns null, and [addAccount]
 * refuses rather than writing a row under a key it could not read back.
 */
class TotpAccountRepository private constructor(
    database: TotpDatabase,
) {
    private val dao = database.accountDao()

    /**
     * Re-scanning a QR for an account that already exists overwrites it instead of
     * adding a duplicate, which is what rotating a secret at the provider looks like
     * from this side.
     */
    fun addAccount(account: Account): StoredAccount {
        val key = Vault.key() ?: error("Vault is locked")
        val encryptedSecret = TotpSecretCipher.encrypt(key, account.secret)
        val existingId = dao.findAccountId(account.issuer, account.label)
        val id = if (existingId != null) {
            val updated = dao.update(
                TotpAccountEntity(
                    id = existingId,
                    issuer = account.issuer,
                    label = account.label,
                    digits = account.digits,
                    period = account.period,
                    algorithm = account.algorithm,
                    encryptedSecret = encryptedSecret,
                ),
            )
            if (updated == 0) error("Failed to update TOTP account")
            existingId
        } else {
            val inserted = dao.insert(
                TotpAccountEntity(
                    issuer = account.issuer,
                    label = account.label,
                    digits = account.digits,
                    period = account.period,
                    algorithm = account.algorithm,
                    encryptedSecret = encryptedSecret,
                ),
            )
            if (inserted == -1L) error("Failed to insert TOTP account")
            inserted
        }
        return StoredAccount(
            id = id,
            issuer = account.issuer,
            label = account.label,
            digits = account.digits,
            period = account.period,
            algorithm = account.algorithm,
        )
    }

    fun getAccount(id: Long): StoredAccount? = dao.getAccount(id)

    fun observeAccounts(): Flow<List<StoredAccount>> = dao.observeAccounts()

    /**
     * A plain list, for LightSync's backup provider.
     *
     * The UI wants a Flow; a backup wants an answer. Exposing this rather than having the
     * provider block on the Flow's first emission keeps the awkwardness in one place.
     */
    fun accountsForBackup(): List<StoredAccount> = dao.accountsSnapshot()

    fun deleteAccount(id: Long): Boolean = dao.deleteAccount(id) > 0

    /** Null while locked, and null for a row the current key cannot open. */
    fun decryptSecret(id: Long): String? {
        val key = Vault.key() ?: return null
        val encrypted = dao.getEncryptedSecret(id) ?: return null
        return TotpSecretCipher.decrypt(key, encrypted)
    }

    /**
     * Re-wraps every row from the AndroidKeyStore key onto the vault key, once.
     *
     * Runs on the first launch after upgrading, while no PIN can exist yet — so the vault is
     * open and this needs no interaction. A row that will not decrypt is left exactly as it
     * is rather than dropped: it is already unreadable, and deleting it would turn a
     * recoverable state into a silently emptier list.
     */
    fun migrateToVault() {
        if (Vault.vaultVersion() >= Vault.CURRENT_VAULT_VERSION) return
        val key = Vault.key() ?: return
        var moved = 0
        var stuck = 0
        for (row in dao.allSecrets()) {
            // Already vault-wrapped? Then this is a re-run and there is nothing to do.
            if (TotpSecretCipher.decrypt(key, row.encryptedSecret) != null) continue
            val plaintext = TotpSecretCipher.legacyDecrypt(row.encryptedSecret)
            if (plaintext == null) {
                stuck++
                continue
            }
            dao.updateSecret(row.id, TotpSecretCipher.encrypt(key, plaintext))
            moved++
        }
        Vault.markMigrated()
        Log.i(TAG, "Vault migration: $moved re-wrapped, $stuck unreadable")
    }

    /** Called by [Vault.wipe]: the key is already gone, so the rows are only noise. */
    fun deleteEverything() {
        dao.deleteAll()
    }

    companion object {
        private const val TAG = "LightAuthVault"
        const val DATABASE_NAME = "totp_accounts.db"

        @Volatile
        private var instance: TotpAccountRepository? = null

        fun getInstance(context: Context): TotpAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: TotpAccountRepository(
                    database = Room.databaseBuilder(
                        context.applicationContext,
                        TotpDatabase::class.java,
                        DATABASE_NAME,
                    ).build(),
                ).also {
                    instance = it
                    // The vault destroys the key on a wipe; the rows have to go with it.
                    Vault.onWipe = { runCatching { it.deleteEverything() } }
                }
            }
        }
    }
}

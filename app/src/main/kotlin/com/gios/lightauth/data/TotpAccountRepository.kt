package com.gios.lightauth.data

import android.content.Context
import androidx.room.Room
import com.gios.lightauth.crypto.TotpSecretCipher
import com.gios.lightauth.totp.Account
import kotlinx.coroutines.flow.Flow

class TotpAccountRepository private constructor(
    database: TotpDatabase,
    private val cipher: TotpSecretCipher,
) {
    private val dao = database.accountDao()

    /**
     * Re-scanning a QR for an account that already exists overwrites it instead of
     * adding a duplicate, which is what rotating a secret at the provider looks like
     * from this side.
     */
    fun addAccount(account: Account): StoredAccount {
        val encryptedSecret = cipher.encrypt(account.secret)
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

    fun deleteAccount(id: Long): Boolean = dao.deleteAccount(id) > 0

    fun decryptSecret(id: Long): String? {
        val encrypted = dao.getEncryptedSecret(id) ?: return null
        return cipher.decrypt(encrypted)
    }

    companion object {
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
                    cipher = TotpSecretCipher(),
                ).also { instance = it }
            }
        }
    }
}

package com.gios.lightauth.data

/**
 * An account as read back out of the database. Deliberately has no secret field —
 * the ciphertext only leaves the DAO through [TotpAccountRepository.decryptSecret],
 * so a list of accounts can be held in UI state without holding any secrets.
 */
data class StoredAccount(
    val id: Long,
    val issuer: String,
    val label: String,
    val digits: Int,
    val period: Int,
    val algorithm: String,
) {
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            label.isNotBlank() -> label
            else -> "Unknown"
        }
}

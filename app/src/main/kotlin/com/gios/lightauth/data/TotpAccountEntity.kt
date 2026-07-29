package com.gios.lightauth.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "totp_accounts")
internal data class TotpAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val issuer: String,
    val label: String,
    val digits: Int,
    val period: Int,
    val algorithm: String,
    /** AES-GCM blob: 12-byte IV followed by ciphertext. Never the raw secret. */
    @ColumnInfo(name = "encrypted_secret") val encryptedSecret: ByteArray,
)

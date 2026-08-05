package com.gios.lightauth.data

import androidx.room.ColumnInfo

/** An id and its ciphertext, which is all the migration needs to re-wrap a row. */
internal data class EncryptedRow(
    val id: Long,
    @ColumnInfo(name = "encrypted_secret") val encryptedSecret: ByteArray,
)

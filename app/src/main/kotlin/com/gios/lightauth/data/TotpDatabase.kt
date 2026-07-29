package com.gios.lightauth.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TotpAccountEntity::class], version = 1, exportSchema = false)
internal abstract class TotpDatabase : RoomDatabase() {
    abstract fun accountDao(): TotpAccountDao
}

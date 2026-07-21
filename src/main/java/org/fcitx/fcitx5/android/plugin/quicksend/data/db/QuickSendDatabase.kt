package org.fcitx.fcitx5.android.plugin.quicksend.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [QuickSendEntry::class], version = 1, exportSchema = false)
@TypeConverters(QuickSendConverters::class)
abstract class QuickSendDatabase : RoomDatabase() {

    abstract fun quickSendDao(): QuickSendDao

    companion object {
        const val NAME = "quicksend_db"
    }
}

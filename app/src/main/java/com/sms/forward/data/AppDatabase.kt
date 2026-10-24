package com.sms.forward.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [SmsEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(SmsStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_forward.db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

class SmsStatusConverter {
    @TypeConverter
    fun fromStatus(status: SmsStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): SmsStatus = runCatching { SmsStatus.valueOf(value) }
        .getOrDefault(SmsStatus.PENDING)
}

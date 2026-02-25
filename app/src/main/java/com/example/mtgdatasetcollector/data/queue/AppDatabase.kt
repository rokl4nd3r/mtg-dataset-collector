package com.example.mtgdatasetcollector.data.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UploadJobEntity::class],
    version = 2, // <<< era 1
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun uploadJobDao(): UploadJobDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "collector.db"
                )
                    // dev-safe: se mudar schema agora, não quebra build/teste
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
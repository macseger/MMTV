package com.example.mmtv.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.mmtv.model.MediaType

@Database(entities = [MediaEntity::class, EpgEntity::class, ChannelEntity::class, PiconEntity::class], version = 14, exportSchema = false)
@TypeConverters(MediaConverters::class)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: MediaDatabase? = null

        fun getDatabase(context: Context): MediaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "mmtv_database"
                )
                .fallbackToDestructiveMigration()
                .addMigrations(MIGRATION_12_13)
                .addMigrations(MIGRATION_13_14)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE media_items ADD COLUMN resolvedIcon TEXT")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_type_categoryId_itemOrder ON media_items(type, categoryId, itemOrder)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isFavorite_favoriteDate ON media_items(isFavorite, favoriteDate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_addedDate ON media_items(addedDate)")
            }
        }
    }
}

class MediaConverters {
    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = try {
        MediaType.valueOf(value)
    } catch (e: Exception) {
        MediaType.MOVIE
    }
}

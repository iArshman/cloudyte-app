package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalCloudFile::class,
        LocalAccount::class,
        LocalUploadRecord::class,
        LocalExclusion::class,
        LocalTransfer::class,
        LocalStorageSummary::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun accountDao(): AccountDao
    abstract fun uploadRecordDao(): UploadRecordDao
    abstract fun exclusionDao(): ExclusionDao
    abstract fun transferDao(): TransferDao
    abstract fun storageSummaryDao(): StorageSummaryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(): AppDatabase? = INSTANCE

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_files ADD COLUMN updatedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE cloud_files ADD COLUMN thumbUrl TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_files_accountId_folderId ON cloud_files(accountId, folderId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS exclusions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pattern TEXT NOT NULL,
                        isRegex INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS transfers (
                        id TEXT PRIMARY KEY NOT NULL,
                        accountId TEXT NOT NULL,
                        fileId TEXT,
                        fileName TEXT NOT NULL,
                        localPath TEXT,
                        remotePath TEXT,
                        direction TEXT NOT NULL,
                        status TEXT NOT NULL,
                        bytesTransferred INTEGER NOT NULL DEFAULT 0,
                        totalBytes INTEGER NOT NULL DEFAULT 0,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS storage_summary (
                        accountId TEXT PRIMARY KEY NOT NULL,
                        accountType TEXT NOT NULL,
                        usedBytes INTEGER NOT NULL DEFAULT 0,
                        totalBytes INTEGER,
                        fileCount INTEGER,
                        categoriesJson TEXT,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cloud_app_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

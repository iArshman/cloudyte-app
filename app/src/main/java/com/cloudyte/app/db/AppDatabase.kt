package com.cloudyte.app.db

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
        LocalStorageSummary::class,
        LocalFolderSyncMeta::class,
        LocalFileMutation::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun accountDao(): AccountDao
    abstract fun uploadRecordDao(): UploadRecordDao
    abstract fun exclusionDao(): ExclusionDao
    abstract fun transferDao(): TransferDao
    abstract fun storageSummaryDao(): StorageSummaryDao
    abstract fun folderSyncMetaDao(): FolderSyncMetaDao
    abstract fun fileMutationDao(): FileMutationDao
    abstract fun transferQueueDao(): TransferQueueDao

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS folder_sync_meta (
                        accountKey TEXT NOT NULL,
                        folderId TEXT NOT NULL,
                        etag TEXT,
                        lastSyncedAt INTEGER NOT NULL,
                        PRIMARY KEY(accountKey, folderId)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_status_updatedAt ON transfers(status, updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_accountId_createdAt ON transfers(accountId, createdAt)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN providerSessionId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE transfers ADD COLUMN chunkSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfers ADD COLUMN nextChunkIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfers ADD COLUMN checksum TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE transfers ADD COLUMN idempotencyKey TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE transfers ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_idempotencyKey ON transfers(idempotencyKey)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_mutations (
                        operationId TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT,
                        operation TEXT NOT NULL,
                        fileId TEXT,
                        name TEXT,
                        parentFolderId TEXT NOT NULL DEFAULT 'root',
                        optimisticFileId TEXT,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_mutations_status_createdAt ON file_mutations(status, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_mutations_accountId_parentFolderId ON file_mutations(accountId, parentFolderId)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN state TEXT NOT NULL DEFAULT 'QUEUED'")
                db.execSQL("ALTER TABLE transfers ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfers ADD COLUMN operationType TEXT NOT NULL DEFAULT 'UPLOAD'")
                db.execSQL("ALTER TABLE transfers ADD COLUMN providerType TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN parentFolderId TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN localUri TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transfers ADD COLUMN etaSeconds INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN lastProgressAt INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN nextAttemptAt INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN leaseOwner TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN leaseExpiresAt INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN lastErrorCode TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN lastErrorMessage TEXT")
                db.execSQL("ALTER TABLE transfers ADD COLUMN lastHttpStatus INTEGER")
                db.execSQL("ALTER TABLE transfers ADD COLUMN correlationId TEXT")
                db.execSQL("""UPDATE transfers SET state = CASE status
                    WHEN 'RUNNING' THEN 'QUEUED'
                    WHEN 'PAUSED' THEN 'PAUSED'
                    WHEN 'COMPLETED' THEN 'COMPLETED'
                    WHEN 'CANCELLED' THEN 'CANCELLED'
                    WHEN 'FAILED' THEN 'FAILED_RETRYABLE'
                    ELSE 'QUEUED' END""")
                db.execSQL("UPDATE transfers SET status='PENDING', errorMessage='Recovered after app update', lastErrorCode='WORKER_INTERRUPTED', lastErrorMessage='Recovered after app update' WHERE status='RUNNING'")
                db.execSQL("UPDATE transfers SET operationType = direction WHERE direction IS NOT NULL")
                db.execSQL("UPDATE transfers SET remoteId = fileId WHERE remoteId IS NULL AND fileId IS NOT NULL")
                db.execSQL("UPDATE transfers SET localUri = localPath WHERE localUri IS NULL AND localPath IS NOT NULL")
                db.execSQL("UPDATE transfers SET parentFolderId = remotePath WHERE parentFolderId IS NULL AND remotePath IS NOT NULL")
                db.execSQL("UPDATE transfers SET providerType = (SELECT accountType FROM local_accounts WHERE local_accounts.id = transfers.accountId) WHERE providerType IS NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_state_priority_createdAt ON transfers(state, priority, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_leaseExpiresAt ON transfers(leaseExpiresAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfers_providerType_remoteId ON transfers(providerType, remoteId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cloud_app_database"
                )
                    .addMigrations(
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

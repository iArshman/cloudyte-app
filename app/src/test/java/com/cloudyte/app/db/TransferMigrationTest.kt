package com.cloudyte.app.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransferMigrationTest {
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var database: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE local_accounts (id TEXT NOT NULL PRIMARY KEY, accountType TEXT NOT NULL)")
                    db.execSQL("INSERT INTO local_accounts(id, accountType) VALUES ('a1', 'CLOUDYTE')")
                    db.execSQL(
                        """CREATE TABLE transfers (
                            id TEXT NOT NULL PRIMARY KEY,
                            accountId TEXT NOT NULL,
                            fileId TEXT,
                            fileName TEXT NOT NULL,
                            localPath TEXT,
                            remotePath TEXT,
                            direction TEXT NOT NULL,
                            status TEXT NOT NULL,
                            bytesTransferred INTEGER NOT NULL,
                            totalBytes INTEGER NOT NULL,
                            errorMessage TEXT,
                            providerSessionId TEXT,
                            chunkSize INTEGER NOT NULL DEFAULT 0,
                            nextChunkIndex INTEGER NOT NULL DEFAULT 0,
                            checksum TEXT,
                            idempotencyKey TEXT,
                            attemptCount INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )""".trimIndent()
                    )
                    db.execSQL("CREATE INDEX index_transfers_status_updatedAt ON transfers(status, updatedAt)")
                    db.execSQL("CREATE INDEX index_transfers_accountId_createdAt ON transfers(accountId, createdAt)")
                    db.execSQL("CREATE INDEX index_transfers_idempotencyKey ON transfers(idempotencyKey)")
                    db.execSQL(
                        "INSERT INTO transfers(id, accountId, fileId, fileName, localPath, remotePath, direction, status, bytesTransferred, totalBytes, createdAt, updatedAt) " +
                            "VALUES ('pending', 'a1', 'remote-1', 'one.bin', '/tmp/one.bin', 'folder-1', 'UPLOAD', 'PENDING', 3, 9, 1, 1)"
                    )
                    db.execSQL(
                        "INSERT INTO transfers(id, accountId, fileName, direction, status, bytesTransferred, totalBytes, createdAt, updatedAt) " +
                            "VALUES ('running', 'a1', 'two.bin', 'BACKUP', 'RUNNING', 4, 10, 2, 2)"
                    )
                    db.execSQL(
                        "INSERT INTO transfers(id, accountId, fileName, direction, status, bytesTransferred, totalBytes, createdAt, updatedAt) " +
                            "VALUES ('failed', 'a1', 'three.bin', 'DOWNLOAD', 'FAILED', 0, 11, 3, 3)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        database = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun migrationBackfillsLegacyRowsAndRequeuesLeaseLessRunningWork() {
        AppDatabase.MIGRATION_9_10.migrate(database)

        database.query("SELECT state, status, operationType, providerType, remoteId, localUri, parentFolderId FROM transfers WHERE id='pending'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("QUEUED", row.getString(0))
            assertEquals("PENDING", row.getString(1))
            assertEquals("UPLOAD", row.getString(2))
            assertEquals("CLOUDYTE", row.getString(3))
            assertEquals("remote-1", row.getString(4))
            assertEquals("/tmp/one.bin", row.getString(5))
            assertEquals("folder-1", row.getString(6))
        }

        database.query("SELECT state, status, lastErrorCode FROM transfers WHERE id='running'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("QUEUED", row.getString(0))
            assertEquals("PENDING", row.getString(1))
            assertEquals("WORKER_INTERRUPTED", row.getString(2))
        }

        database.query("SELECT state FROM transfers WHERE id='failed'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("FAILED_RETRYABLE", row.getString(0))
        }

        database.query("PRAGMA table_info(transfers)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(1)
            assertTrue(columns.containsAll(setOf("state", "leaseOwner", "leaseExpiresAt", "correlationId", "nextAttemptAt")))
        }
    }

    @Test
    fun migration12To13CreatesThumbnailCacheTable() {
        AppDatabase.MIGRATION_12_13.migrate(database)

        database.query("PRAGMA table_info(thumbnail_cache)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(1)
            assertTrue(columns.containsAll(setOf(
                "cacheKey", "accountId", "providerType", "remoteId", "sourceRevision",
                "path", "mimeType", "width", "height", "sizeBytes", "isEncrypted",
                "generatedAt", "lastAccessAt", "failureCount", "nextRetryAt"
            )))
        }

        database.execSQL("""
            INSERT INTO thumbnail_cache(
                cacheKey, accountId, providerType, remoteId, path, mimeType,
                sizeBytes, generatedAt, lastAccessAt
            ) VALUES ('k', 'a1', 'CLOUDYTE', 'remote-1', '/tmp/k.jpg', 'image/jpeg', 10, 1, 1)
        """.trimIndent())
        database.query("SELECT accountId, remoteId, sizeBytes FROM thumbnail_cache WHERE cacheKey='k'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("a1", row.getString(0))
            assertEquals("remote-1", row.getString(1))
            assertEquals(10L, row.getLong(2))
        }
    }
}

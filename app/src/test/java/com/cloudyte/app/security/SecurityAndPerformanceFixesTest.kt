package com.cloudyte.app.security

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.core.network.DefaultNetworkPolicy
import com.cloudyte.app.core.network.NetworkPolicySnapshot
import com.cloudyte.app.core.network.NetworkStatus
import com.cloudyte.app.core.network.PolicyDecision
import com.cloudyte.app.core.security.ContentKeyMaterial
import com.cloudyte.app.core.security.CryptoConstants
import com.cloudyte.app.core.security.KeyBackupCodec
import com.cloudyte.app.core.security.KeyBackupException
import com.cloudyte.app.core.security.PassphraseKdf
import com.cloudyte.app.data.AccountSessionHelper
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.BackupSourceEntity
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalExclusion
import com.cloudyte.app.db.LocalFileMutation
import com.cloudyte.app.db.LocalFileTombstone
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.LocalUploadRecord
import com.cloudyte.app.db.TransferDirection
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SecurityAndPerformanceFixesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ApiClient.saveToken(null)
    }

    @After
    fun tearDown() {
        ApiClient.saveToken(null)
    }

    @Test
    fun apiClient_urlsDoNotContainQueryTokens_andAuthInterceptorScopesToTrustedHttpsOrigin() {
        ApiClient.saveToken("secret-jwt-token-123")

        val thumbUrl = ApiClient.getThumbUrl("acc_1", "file_1", size = 256)
        val cleanUrl = ApiClient.getCleanDownloadUrl("acc_1", "file_1", preview = true)
        val fileUrl = ApiClient.getFileUrl("acc_1", "file_1", preview = false)

        assertFalse("Thumb URL must not leak token query param", thumbUrl.contains("token="))
        assertFalse("Download URL must not leak token query param", cleanUrl.contains("token="))
        assertFalse("File URL must not leak token query param", fileUrl.contains("token="))

        var capturedRequest: Request? = null
        val testClient = ApiClient.okHttpClient.newBuilder()
            .addInterceptor(Interceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        // 1. Trusted HTTPS API origin gets Bearer token
        testClient.newCall(Request.Builder().url("${ApiClient.API_BASE_URL}/api/files").build()).execute().close()
        assertEquals("Bearer secret-jwt-token-123", capturedRequest?.header("Authorization"))

        // 2. Login endpoint does NOT receive Bearer token
        testClient.newCall(Request.Builder().url("${ApiClient.API_BASE_URL}/api/login").build()).execute().close()
        assertNull(capturedRequest?.header("Authorization"))

        // 3. Untrusted external HTTPS host must NEVER receive Bearer token
        testClient.newCall(Request.Builder().url("https://evil.example.com/steal").build()).execute().close()
        assertNull(capturedRequest?.header("Authorization"))

        // 4. Plaintext HTTP even on same host must NEVER receive Bearer token
        val apiHost = ApiClient.API_BASE_URL.removePrefix("https://").substringBefore("/")
        testClient.newCall(Request.Builder().url("http://$apiHost/api/files").build()).execute().close()
        assertNull(capturedRequest?.header("Authorization"))
    }

    @Test
    fun kdfAndKeyBackupCodec_rejectExcessiveIterationCounts() {
        val salt = ByteArray(CryptoConstants.PBKDF2_SALT_LENGTH_BYTES) { 7 }
        try {
            PassphraseKdf.deriveKey(
                passphrase = "strong-passphrase".toCharArray(),
                salt = salt,
                iterations = CryptoConstants.MAX_PBKDF2_ITERATIONS + 1
            )
            fail("Expected KeyBackupException for excessive PBKDF2 iterations")
        } catch (_: KeyBackupException) {
            // Expected
        }

        val codec = KeyBackupCodec()
        val keyMaterial = ContentKeyMaterial("key_1", ByteArray(CryptoConstants.AES_KEY_SIZE_BYTES) { 1 })
        val backupJson = try {
            val raw = codec.createKeyBackupForMaterial("strong-passphrase".toCharArray(), keyMaterial)
            JSONObject(String(raw, Charsets.UTF_8))
        } finally {
            keyMaterial.destroy()
        }
        backupJson.put("kdfIterations", CryptoConstants.MAX_PBKDF2_ITERATIONS + 500)
        assertFalse(
            "Backup with excessive iteration count must fail validation",
            codec.validateKeyBackup(backupJson.toString().toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun offlineFileManager_refreshDownloadedSetParsesUnderscoreIdsCorrectly() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        db.cloudFileDao().clearAll()
        db.accountDao().clearAccounts()

        val accountId = "tg_123_456"
        val fileId = "msg_789_01"
        db.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = accountId,
                    name = "TG Drive",
                    driveName = "TG Drive",
                    email = "tg@example.com",
                    accountType = "tgdrive",
                    usageBytes = 0L,
                    totalBytes = 1000L,
                    percentage = 0f,
                    fileCount = 1,
                    isDefault = false,
                    channelId = "123"
                )
            )
        )
        db.cloudFileDao().insertFiles(
            listOf(
                LocalCloudFile(
                    id = fileId,
                    name = "holiday_photo.jpg",
                    type = "image",
                    size = "10 B",
                    accountId = accountId,
                    folderId = "root"
                )
            )
        )

        OfflineFileManager.clearAllOfflineCache(context)
        val offlineFile = OfflineFileManager.getLocalFile(context, accountId, fileId, "holiday_photo.jpg")
        offlineFile.writeText("sample-data")
        try {
            OfflineFileManager.refreshDownloadedSet(context)
            assertTrue(
                "Expected underscore fileId '$fileId' to be recognized in downloadedFiles",
                OfflineFileManager.downloadedFiles.value.contains(fileId)
            )
        } finally {
            offlineFile.delete()
            OfflineFileManager.clearAllOfflineCache(context)
            db.cloudFileDao().clearAll()
            db.accountDao().clearAccounts()
        }
    }

    @Test
    fun migration15To16_createsExpectedUploadRecordAndCloudFileIndices() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS cloud_files (
                                id TEXT NOT NULL PRIMARY KEY,
                                name TEXT NOT NULL,
                                type TEXT NOT NULL,
                                size TEXT NOT NULL,
                                rawSize INTEGER NOT NULL,
                                time INTEGER NOT NULL,
                                isFolder INTEGER NOT NULL,
                                path TEXT NOT NULL,
                                folderId TEXT NOT NULL,
                                accountId TEXT,
                                mimeType TEXT,
                                shareId TEXT,
                                isShared INTEGER NOT NULL,
                                cachedAt INTEGER NOT NULL,
                                updatedAt INTEGER,
                                thumbUrl TEXT,
                                isEncrypted INTEGER NOT NULL DEFAULT 0,
                                encryptionFormatVersion INTEGER,
                                encryptionKeyId TEXT,
                                encryptedMetadata TEXT,
                                encryptedSizeBytes INTEGER,
                                encryptedChecksum TEXT,
                                opaqueRemoteName TEXT,
                                plaintextSizeBytes INTEGER,
                                originalMetadataAvailable INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS upload_records (
                                recordId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                localUri TEXT NOT NULL,
                                fileName TEXT NOT NULL,
                                fileSize INTEGER NOT NULL,
                                lastModified INTEGER NOT NULL,
                                targetAccountId TEXT NOT NULL,
                                targetFolderId TEXT NOT NULL,
                                cloudFileId TEXT,
                                uploadedAt INTEGER NOT NULL,
                                status TEXT NOT NULL,
                                contentHash TEXT
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_15_16.migrate(db)

            val cloudFileIndices = mutableSetOf<String>()
            db.query("PRAGMA index_list(cloud_files)").use { cursor ->
                while (cursor.moveToNext()) cloudFileIndices += cursor.getString(1)
            }
            assertTrue(cloudFileIndices.contains("index_cloud_files_isEncrypted"))
            assertTrue(cloudFileIndices.contains("index_cloud_files_isFolder_time_updatedAt"))

            val uploadIndices = mutableSetOf<String>()
            db.query("PRAGMA index_list(upload_records)").use { cursor ->
                while (cursor.moveToNext()) uploadIndices += cursor.getString(1)
            }
            assertTrue(uploadIndices.contains("index_upload_records_localUri_targetAccountId_targetFolderId_status_uploadedAt"))
            assertTrue(uploadIndices.contains("index_upload_records_contentHash_fileSize_targetAccountId_targetFolderId_status_uploadedAt"))
            assertTrue(uploadIndices.contains("index_upload_records_fileName_fileSize_targetAccountId_status"))
        } finally {
            helper.close()
        }
    }

    @Test
    fun performLogout_wipesAllRoomTablesTokensAndDrivePrefs() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        ApiClient.saveToken("active-session-jwt")
        context.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE)
            .edit().putString("selected_drive", "acc-1").apply()

        db.accountDao().insertAccounts(
            listOf(LocalAccount("acc-1", "Drive", "Drive", "a@b.com", "cloudyte", 0L, 100L, 0f, 1, true, null))
        )
        db.cloudFileDao().insertFiles(
            listOf(LocalCloudFile("f-1", "secret.txt", "file", "1 KB", accountId = "acc-1"))
        )
        db.transferDao().insert(
            LocalTransfer(id = "tr-1", accountId = "acc-1", fileName = "secret.txt")
        )
        db.backupSourceDao().upsert(
            BackupSourceEntity(id = "src-1", treeUri = "file:///tmp/src", displayName = "Src")
        )
        db.exclusionDao().insert(LocalExclusion(pattern = "*.tmp"))
        db.fileMutationDao().insert(
            LocalFileMutation(operationId = "op-1", accountId = "acc-1", operation = LocalFileMutation.DELETE, fileId = "f-1")
        )
        db.fileTombstoneDao().insertAll(
            listOf(LocalFileTombstone("tomb-1", "acc-1", "cloudyte", "f-1", "root", 1L, null))
        )
        db.uploadRecordDao().insertRecord(
            LocalUploadRecord(localUri = "file:///a", fileName = "a", fileSize = 1L, lastModified = 1L, targetAccountId = "acc-1", targetFolderId = "root")
        )

        val result = AccountSessionHelper.performLogout(context) { true }
        assertTrue(result.remoteRevoked)
        assertNull(ApiClient.getToken())
        assertTrue(db.accountDao().getAllAccounts().isEmpty())
        assertTrue(db.cloudFileDao().getAllFiles().isEmpty())
        assertTrue(db.transferDao().getById("tr-1") == null)
        assertTrue(db.backupSourceDao().getAll().isEmpty())
        assertTrue(db.exclusionDao().getEnabledExclusions().isEmpty())
        assertTrue(db.fileMutationDao().pending().isEmpty())
        assertTrue(db.fileTombstoneDao().forFolder("acc-1", "root").isEmpty())
        assertTrue(db.uploadRecordDao().getAllRecords().isEmpty())
        assertTrue(
            context.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE).all.isEmpty()
        )
    }

    @Test
    fun defaultNetworkPolicy_manualBackupBypassesWifiAndChargingGatesUnlessOffline() {
        val policy = DefaultNetworkPolicy()
        val strictMeteredUnpluggedSnapshot = NetworkPolicySnapshot(
            status = NetworkStatus.VALIDATED_METERED,
            allowMeteredTransfers = false,
            allowRoaming = false,
            requireChargingForBackup = true,
            batterySaverActive = true,
            isRoaming = false,
            isCharging = false,
            batteryNotLow = false,
            requireWifiForBackup = true
        )
        val autoBackup = LocalTransfer(
            id = "auto-b",
            accountId = "acc-1",
            fileName = "f.jpg",
            direction = TransferDirection.BACKUP.name,
            operationType = "BACKUP"
        )
        val manualBackup = LocalTransfer(
            id = "manual-b",
            accountId = "acc-1",
            fileName = "f.jpg",
            direction = TransferDirection.BACKUP.name,
            operationType = "MANUAL_BACKUP"
        )

        assertTrue(policy.canStart(autoBackup, strictMeteredUnpluggedSnapshot) !is PolicyDecision.Allowed)
        assertEquals(PolicyDecision.Allowed, policy.canStart(manualBackup, strictMeteredUnpluggedSnapshot))

        val offlineSnapshot = strictMeteredUnpluggedSnapshot.copy(status = NetworkStatus.UNAVAILABLE)
        assertTrue(policy.canStart(manualBackup, offlineSnapshot) is PolicyDecision.WaitingForNetwork)
    }

    @Test
    fun transferQueueDao_aggregateProgressTotalsMatchesIndividualQueries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.transferDao().insert(
                LocalTransfer(id = "q1", accountId = "a1", fileName = "a", state = "QUEUED", bytesTransferred = 120L, totalBytes = 500L)
            )
            db.transferDao().insert(
                LocalTransfer(id = "r1", accountId = "a1", fileName = "b", state = "RUNNING", bytesTransferred = 380L, totalBytes = 1000L)
            )
            db.transferDao().insert(
                LocalTransfer(id = "c1", accountId = "a1", fileName = "c", state = "COMPLETED", bytesTransferred = 999L, totalBytes = 999L)
            )
            val queueDao = db.transferQueueDao()
            val totals = queueDao.aggregateProgressTotals()
            assertEquals(queueDao.aggregateBytesTransferred(), totals.bytesTransferred)
            assertEquals(queueDao.aggregateTotalBytes(), totals.totalBytes)
            assertEquals(500L, totals.bytesTransferred)
            assertEquals(1500L, totals.totalBytes)
        } finally {
            db.close()
        }
    }
}

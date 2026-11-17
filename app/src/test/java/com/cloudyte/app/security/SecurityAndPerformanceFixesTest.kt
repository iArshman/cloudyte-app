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
import com.cloudyte.app.data.UserPreferences
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
import com.cloudyte.app.db.TransferStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test
    fun accountSessionHelper_purgeDisconnectedAccountRemovesOnlyTargetAccountData() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        db.clearAllTables()
        OfflineFileManager.clearAllOfflineCache(context)

        val removedAcc = "acc-removed"
        val keptAcc = "acc-kept"

        db.accountDao().insertAccounts(
            listOf(
                LocalAccount(removedAcc, "Removed", "Removed", "removed@example.com", "gdrive", 0L, 100L, 0f, 1, true, null),
                LocalAccount(keptAcc, "Kept", "Kept", "kept@example.com", "onedrive", 0L, 100L, 0f, 1, false, null)
            )
        )
        db.cloudFileDao().insertFiles(
            listOf(
                LocalCloudFile("file-rem", "rem.txt", "file", "10 B", accountId = removedAcc),
                LocalCloudFile("file-kept", "kept.txt", "file", "20 B", accountId = keptAcc)
            )
        )
        db.transferDao().insert(
            LocalTransfer(id = "tx-rem", accountId = removedAcc, fileName = "rem.txt", direction = TransferDirection.UPLOAD.name, status = TransferStatus.PENDING.name)
        )
        db.transferDao().insert(
            LocalTransfer(id = "tx-kept", accountId = keptAcc, fileName = "kept.txt", direction = TransferDirection.UPLOAD.name, status = TransferStatus.PENDING.name)
        )
        db.fileMutationDao().insert(
            LocalFileMutation(operationId = "mut-rem", accountId = removedAcc, operation = LocalFileMutation.RENAME, fileId = "file-rem")
        )
        db.fileMutationDao().insert(
            LocalFileMutation(operationId = "mut-kept", accountId = keptAcc, operation = LocalFileMutation.RENAME, fileId = "file-kept")
        )
        db.fileTombstoneDao().insertAll(
            listOf(
                LocalFileTombstone("tomb-rem", removedAcc, "gdrive", "file-rem", "root", 1L, null),
                LocalFileTombstone("tomb-kept", keptAcc, "onedrive", "file-kept", "root", 2L, null)
            )
        )
        db.uploadRecordDao().insertRecord(
            LocalUploadRecord(localUri = "content://rem/1", fileName = "1.jpg", fileSize = 10L, lastModified = 1L, targetAccountId = removedAcc, targetFolderId = "root")
        )
        db.uploadRecordDao().insertRecord(
            LocalUploadRecord(localUri = "content://kept/2", fileName = "2.jpg", fileSize = 20L, lastModified = 2L, targetAccountId = keptAcc, targetFolderId = "root")
        )

        val remVaultFile = OfflineFileManager.getLocalFile(context, removedAcc, "file-rem", "rem.txt").apply { writeText("secret-rem") }
        val keptVaultFile = OfflineFileManager.getLocalFile(context, keptAcc, "file-kept", "kept.txt").apply { writeText("secret-kept") }
        OfflineFileManager.refreshDownloadedSet(context)
        assertTrue(OfflineFileManager.downloadedFiles.value.contains("file-rem"))
        assertTrue(OfflineFileManager.downloadedFiles.value.contains("file-kept"))

        UserPreferences.setActiveAndHomeAccountId(context, removedAcc)
        UserPreferences.setBackupAccountId(context, removedAcc)
        context.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE)
            .edit()
            .putString("last_active_account_id", removedAcc)
            .commit()

        AccountSessionHelper.purgeDisconnectedAccount(context, removedAcc)
        OfflineFileManager.refreshDownloadedSet(context)

        // Removed account data must be completely purged while kept account data remains intact
        assertEquals(listOf(keptAcc), db.accountDao().getAllAccounts().map { it.id })
        assertTrue(db.cloudFileDao().getFilesForAccount(removedAcc).isEmpty())
        assertEquals(1, db.cloudFileDao().getFilesForAccount(keptAcc).size)
        assertTrue(db.transferDao().getByAccountId(removedAcc).isEmpty())
        assertEquals(1, db.transferDao().getByAccountId(keptAcc).size)
        assertEquals(listOf(keptAcc), db.fileMutationDao().pending().map { it.accountId })
        assertTrue(db.fileTombstoneDao().forFolder(removedAcc, "root").isEmpty())
        assertEquals(1, db.fileTombstoneDao().forFolder(keptAcc, "root").size)
        assertEquals(listOf(keptAcc), db.uploadRecordDao().getAllRecords().map { it.targetAccountId })
        assertFalse(remVaultFile.exists())
        assertTrue(keptVaultFile.exists())
        assertFalse(OfflineFileManager.downloadedFiles.value.contains("file-rem"))
        assertTrue(OfflineFileManager.downloadedFiles.value.contains("file-kept"))
        val prefsAfter = UserPreferences.observe(context).first()
        assertNull(prefsAfter.lastActiveAccountId)
        assertNull(prefsAfter.homeStorageAccountId)
        assertNull(prefsAfter.backupAccountId)
        assertNull(
            context.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE)
                .getString("last_active_account_id", null)
        )
    }

    @Test
    fun backendContractUpgrades_errorEnvelope_directStream_resume_and_409Reconciliation() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        // 1. Standard error envelope parsing (Section 3.0)
        val conflictEnvelope = ApiClient.parseErrorEnvelope(
            """{"error":"Chunk offset mismatch","code":"UPLOAD_SESSION_OFFSET_CONFLICT","requestId":"req-99","retryable":true}""",
            409
        )
        assertEquals("Chunk offset mismatch", conflictEnvelope.message)
        assertEquals("UPLOAD_SESSION_OFFSET_CONFLICT", conflictEnvelope.code)
        assertEquals("req-99", conflictEnvelope.requestId)
        assertEquals(true, conflictEnvelope.retryable)

        val retryableConflictResult = com.cloudyte.app.ui.screens.UploadResult(
            isSuccess = false,
            statusCode = 409,
            errorMessage = conflictEnvelope.message,
            errorCode = conflictEnvelope.code,
            serverRetryable = conflictEnvelope.retryable
        )
        assertFalse(retryableConflictResult.isFatalClientError)
        assertTrue(retryableConflictResult.isRetryable)

        // 2. Direct stream chunk upload + auto-resume paused session + 409 offset conflict reconciliation
        val tempFile = java.io.File(context.cacheDir, "contract_test_payload.bin")
        val payload = ByteArray(160 * 1024) { (it % 251).toByte() }
        tempFile.writeBytes(payload)

        var resumeCalls = 0
        var directStreamCalls = 0
        var simulatedConflictTriggered = false
        var serverReceivedBytes = 0L

        val fakeProvider = object : com.cloudyte.app.data.providers.CloudProvider {
            override val type: String = "cloudyte"
            override val capabilities = com.cloudyte.app.data.providers.ProviderCapabilities(
                supportsResumableUpload = true,
                supportsProviderNativeResumability = true,
                supportsProviderSessionCancellation = true,
                supportsResumableDownload = true
            )

            override suspend fun createUploadSession(
                accountId: String,
                request: com.cloudyte.app.api.UploadSessionRequest
            ): retrofit2.Response<com.cloudyte.app.api.UploadSessionResponse> {
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadSessionResponse(
                        sessionId = "sess-upgrade-1",
                        status = "paused",
                        receivedBytes = 0L,
                        totalBytes = payload.size.toLong(),
                        chunkSize = 64L * 1024L
                    )
                )
            }

            override suspend fun queryUploadSession(
                sessionId: String
            ): retrofit2.Response<com.cloudyte.app.api.UploadSessionResponse> {
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadSessionResponse(
                        sessionId = sessionId,
                        status = if (resumeCalls == 0) "paused" else "active",
                        receivedBytes = serverReceivedBytes,
                        totalBytes = payload.size.toLong(),
                        chunkSize = 64L * 1024L
                    )
                )
            }

            override suspend fun resumeUploadSession(
                sessionId: String
            ): retrofit2.Response<com.cloudyte.app.api.UploadSessionResponse> {
                resumeCalls++
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadSessionResponse(
                        sessionId = sessionId,
                        status = "active",
                        receivedBytes = serverReceivedBytes,
                        totalBytes = payload.size.toLong(),
                        chunkSize = 64L * 1024L
                    )
                )
            }

            override suspend fun uploadChunk(
                sessionId: String,
                contentRange: String,
                chunk: okhttp3.MultipartBody.Part
            ): retrofit2.Response<com.cloudyte.app.api.UploadChunkResponse> {
                throw AssertionError("uploadFileResumable should invoke uploadChunkStream instead of multipart uploadChunk")
            }

            override suspend fun uploadChunkStream(
                sessionId: String,
                contentRange: String,
                chunkOffset: Long,
                chunk: okhttp3.RequestBody
            ): retrofit2.Response<com.cloudyte.app.api.UploadChunkResponse> {
                directStreamCalls++
                assertEquals("application/octet-stream", chunk.contentType().toString())
                if (!simulatedConflictTriggered && chunkOffset == 64L * 1024L) {
                    simulatedConflictTriggered = true
                    // Simulate that the server had already committed this chunk on an earlier attempt
                    serverReceivedBytes = 128L * 1024L
                    val errBody = """{"error":"Offset conflict","code":"UPLOAD_SESSION_OFFSET_CONFLICT","retryable":true}"""
                        .toResponseBody("application/json".toMediaTypeOrNull())
                    return retrofit2.Response.error(409, errBody)
                }
                val sink = okio.Buffer()
                chunk.writeTo(sink)
                val written = sink.size
                serverReceivedBytes = chunkOffset + written
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadChunkResponse(
                        sessionId = sessionId,
                        receivedBytes = serverReceivedBytes,
                        totalBytes = payload.size.toLong(),
                        nextOffset = serverReceivedBytes,
                        complete = serverReceivedBytes >= payload.size.toLong()
                    )
                )
            }

            override suspend fun completeUploadSession(
                sessionId: String
            ): retrofit2.Response<com.cloudyte.app.api.UploadCompleteResponse> {
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadCompleteResponse(
                        success = serverReceivedBytes == payload.size.toLong(),
                        sessionId = sessionId
                    )
                )
            }
        }

        try {
            val uploadRes = com.cloudyte.app.ui.screens.UploadHelper.uploadFileResumable(
                context = context,
                accountId = "acc-1",
                folderId = "root",
                uri = android.net.Uri.fromFile(tempFile),
                explicitFileName = tempFile.name,
                explicitMimeType = "application/octet-stream",
                explicitFileSize = payload.size.toLong(),
                idempotencyKey = "idem-upgrade-1",
                provider = fakeProvider
            )
            assertTrue("Expected resumable upload to succeed after auto-resume and 409 reconciliation", uploadRes.isSuccess)
            assertEquals(1, resumeCalls)
            assertTrue(simulatedConflictTriggered)
            assertEquals(3, directStreamCalls)
            assertEquals(payload.size.toLong(), serverReceivedBytes)
        } finally {
            tempFile.delete()
        }
    }
}


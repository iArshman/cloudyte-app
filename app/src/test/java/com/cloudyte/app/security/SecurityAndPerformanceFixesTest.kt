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
import com.cloudyte.app.core.network.isOnline
import com.cloudyte.app.core.security.ContentKeyMaterial
import com.cloudyte.app.core.security.CryptoConstants
import com.cloudyte.app.core.security.KeyBackupCodec
import com.cloudyte.app.core.security.KeyBackupException
import com.cloudyte.app.core.security.PassphraseKdf
import com.cloudyte.app.data.AccountSessionHelper
import com.cloudyte.app.data.TransferRepository
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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun backgroundConnectionAbortRecovery_downloadResumesMidStreamViaRangeWithoutFailing(): Unit = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        db.transferDao().clearAll()
        db.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = "acc-bg-dl",
                    name = "Cloudyte",
                    driveName = "Main",
                    email = "u@cloudyte.io",
                    accountType = "cloudyte",
                    usageBytes = 0L,
                    totalBytes = 10_000L,
                    percentage = 0f,
                    fileCount = 0,
                    isDefault = true,
                    channelId = null
                )
            )
        )

        val fullPayload = ByteArray(100_000) { (it % 199).toByte() }
        val splitPoint = 40_000
        val destFile = File(context.cacheDir, "bg_abort_recover.bin").apply { delete() }
        File(destFile.parentFile, "${destFile.name}.part").delete()

        var requestCount = 0
        val observedRanges = mutableListOf<String?>()

        val fakeApi = object : com.cloudyte.app.api.CloudyteApi by createNoopApiProxy() {
            override suspend fun downloadFile(
                accountId: String,
                fileId: String,
                preview: Int?,
                range: String?
            ): retrofit2.Response<okhttp3.ResponseBody> {
                requestCount++
                observedRanges += range
                if (requestCount == 1) {
                    // Stream first 40,000 bytes, then abort with ECONNABORTED mid-body
                    val partialStream = object : java.io.InputStream() {
                        private var idx = 0
                        override fun read(): Int {
                            if (idx >= splitPoint) throw java.net.SocketException("Software caused connection abort")
                            return fullPayload[idx++].toInt() and 0xFF
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (idx >= splitPoint) throw java.net.SocketException("Software caused connection abort")
                            val toCopy = minOf(len, splitPoint - idx)
                            System.arraycopy(fullPayload, idx, b, off, toCopy)
                            idx += toCopy
                            return toCopy
                        }
                    }
                    val body = object : okhttp3.ResponseBody() {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength() = fullPayload.size.toLong()
                        override fun source(): okio.BufferedSource = okioStreamSource(partialStream).buffer()
                    }
                    return retrofit2.Response.success(body)
                }

                // Attempt 2: server honors Range header from splitPoint onward
                assertEquals("bytes=$splitPoint-", range)
                val remaining = fullPayload.copyOfRange(splitPoint, fullPayload.size)
                val rawResp = Response.Builder()
                    .request(Request.Builder().url("https://cloudyte.local/download").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .headers(
                        okhttp3.Headers.headersOf(
                            "Content-Range", "bytes $splitPoint-${fullPayload.size - 1}/${fullPayload.size}",
                            "Content-Length", remaining.size.toString()
                        )
                    )
                    .build()
                return retrofit2.Response.success(
                    remaining.toResponseBody("application/octet-stream".toMediaTypeOrNull()),
                    rawResp
                )
            }
        }
        val registry = com.cloudyte.app.data.providers.ProviderRegistry(fakeApi)
        val executor = com.cloudyte.app.core.transfer.CloudTransferOperationExecutor(
            context = context,
            database = db,
            transfers = TransferRepository(db.transferDao(), registry),
            providers = registry,
            inlineIoRetryDelayMs = { 0L }
        )

        val row = LocalTransfer(
            id = "dl-bg-abort-1",
            accountId = "acc-bg-dl",
            fileId = "remote-bg-1",
            remoteId = "remote-bg-1",
            fileName = destFile.name,
            localPath = destFile.absolutePath,
            direction = TransferDirection.DOWNLOAD.name,
            operationType = "DOWNLOAD",
            providerType = "cloudyte",
            status = "RUNNING",
            state = com.cloudyte.app.core.transfer.TransferState.RUNNING.name,
            totalBytes = fullPayload.size.toLong(),
            leaseOwner = "owner-bg",
            leaseExpiresAt = System.currentTimeMillis() + 60_000L
        )
        db.transferDao().insert(row)

        try {
            val outcome = executor.execute(row, "owner-bg") { _, _, _, _ -> }
            assertEquals(com.cloudyte.app.core.transfer.TransferOutcome.Completed, outcome)
            assertEquals(listOf(null, "bytes=$splitPoint-"), observedRanges)
            assertTrue(destFile.exists())
            assertArrayEquals(fullPayload, destFile.readBytes())
        } finally {
            destFile.delete()
            File(destFile.parentFile, "${destFile.name}.part").delete()
            db.transferDao().clearAll()
        }
    }

    @Test
    fun backgroundConnectionAbortRecovery_uploadChunksReconcileAndResumeOnSocketException(): Unit = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "bg_abort_upload.bin")
        val payload = ByteArray(140 * 1024) { (it % 233).toByte() }
        tempFile.writeBytes(payload)

        var serverOffset = 0L
        var injectedAbort = false
        var chunkAttempts = 0

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
            ): retrofit2.Response<com.cloudyte.app.api.UploadSessionResponse> =
                retrofit2.Response.success(
                    com.cloudyte.app.api.UploadSessionResponse(
                        sessionId = "sess-abort-1",
                        status = "active",
                        receivedBytes = 0L,
                        totalBytes = payload.size.toLong(),
                        chunkSize = 64L * 1024L
                    )
                )

            override suspend fun queryUploadSession(
                sessionId: String
            ): retrofit2.Response<com.cloudyte.app.api.UploadSessionResponse> =
                retrofit2.Response.success(
                    com.cloudyte.app.api.UploadSessionResponse(
                        sessionId = sessionId,
                        status = "active",
                        receivedBytes = serverOffset,
                        totalBytes = payload.size.toLong(),
                        chunkSize = 64L * 1024L
                    )
                )

            override suspend fun uploadChunk(
                sessionId: String,
                contentRange: String,
                chunk: okhttp3.MultipartBody.Part
            ): retrofit2.Response<com.cloudyte.app.api.UploadChunkResponse> =
                error("Unexpected multipart uploadChunk in stream test")

            override suspend fun uploadChunkStream(
                sessionId: String,
                contentRange: String,
                chunkOffset: Long,
                chunk: okhttp3.RequestBody
            ): retrofit2.Response<com.cloudyte.app.api.UploadChunkResponse> {
                chunkAttempts++
                if (!injectedAbort && chunkOffset == 64L * 1024L) {
                    injectedAbort = true
                    throw java.net.SocketException("Software caused connection abort")
                }
                val sink = okio.Buffer()
                chunk.writeTo(sink)
                serverOffset = chunkOffset + sink.size
                return retrofit2.Response.success(
                    com.cloudyte.app.api.UploadChunkResponse(
                        sessionId = sessionId,
                        receivedBytes = serverOffset,
                        totalBytes = payload.size.toLong(),
                        nextOffset = serverOffset,
                        complete = serverOffset >= payload.size.toLong()
                    )
                )
            }

            override suspend fun completeUploadSession(
                sessionId: String
            ): retrofit2.Response<com.cloudyte.app.api.UploadCompleteResponse> =
                retrofit2.Response.success(
                    com.cloudyte.app.api.UploadCompleteResponse(
                        success = serverOffset == payload.size.toLong(),
                        sessionId = sessionId
                    )
                )
        }

        try {
            val result = com.cloudyte.app.ui.screens.UploadHelper.uploadFileResumable(
                context = context,
                accountId = "acc-ul",
                folderId = "root",
                uri = android.net.Uri.fromFile(tempFile),
                explicitFileName = tempFile.name,
                explicitMimeType = "application/octet-stream",
                explicitFileSize = payload.size.toLong(),
                idempotencyKey = "idem-abort-1",
                provider = fakeProvider
            )
            assertTrue("Upload should recover inline from SocketException", result.isSuccess)
            assertTrue(injectedAbort)
            assertEquals(4, chunkAttempts) // chunk 1 + failed chunk 2 + retried chunk 2 + chunk 3
            assertEquals(payload.size.toLong(), serverOffset)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun transferForegroundService_startsForInteractiveTriggersAndBuildsNotification() {
        val started = com.cloudyte.app.core.transfer.TransferForegroundService.startIfAllowed(
            context,
            com.cloudyte.app.core.transfer.DrainTrigger.USER_ENQUEUE
        )
        assertTrue(started)
        val nextIntent = org.robolectric.Shadows.shadowOf(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        ).nextStartedService
        assertNotNull(nextIntent)
        assertEquals(
            com.cloudyte.app.core.transfer.TransferForegroundService::class.java.name,
            nextIntent.component?.className
        )

        val notification = com.cloudyte.app.core.transfer.notification.TransferNotification.buildNotification(
            context = context,
            transferred = 512L * 1024L,
            total = 2L * 1024L * 1024L,
            currentFile = "archive.zip",
            showDetailedProgress = true
        )
        assertEquals("Transferring archive.zip", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test
    fun connectivityNetworkMonitor_requiresValidatedCapability_andHandlesCaptiveAndMetered() {
        var routeChanges = 0
        val monitor = com.cloudyte.app.core.network.ConnectivityNetworkMonitor(context) {
            routeChanges++
        }

        // 1. Null capabilities -> UNAVAILABLE
        assertEquals(NetworkStatus.UNAVAILABLE, monitor.classifyCapabilities(null))
        assertFalse(NetworkStatus.UNAVAILABLE.isOnline())

        // 2. INTERNET without VALIDATED (e.g. flaky/connecting Wi-Fi) -> UNAVAILABLE
        val unvalidatedCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(unvalidatedCaps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        assertEquals(NetworkStatus.UNAVAILABLE, monitor.classifyCapabilities(unvalidatedCaps))

        // 3. Captive portal -> CAPTIVE_PORTAL
        val captiveCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(captiveCaps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        assertEquals(NetworkStatus.CAPTIVE_PORTAL, monitor.classifyCapabilities(captiveCaps))

        // 4. Validated metered -> VALIDATED_METERED (online)
        val meteredCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(meteredCaps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        assertEquals(NetworkStatus.VALIDATED_METERED, monitor.classifyCapabilities(meteredCaps))

        // 5. Validated unmetered -> VALIDATED_UNMETERED (online)
        val unmeteredCaps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(unmeteredCaps).apply {
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        assertEquals(NetworkStatus.VALIDATED_UNMETERED, monitor.classifyCapabilities(unmeteredCaps))
        assertTrue(NetworkStatus.VALIDATED_UNMETERED.isOnline())
        assertTrue(NetworkStatus.VALIDATED_METERED.isOnline())
        assertFalse(NetworkStatus.CAPTIVE_PORTAL.isOnline())
        assertFalse(NetworkStatus.UNAVAILABLE.isOnline())

        // 6. Synchronous refresh detects offline -> online transition and invokes onRouteChanged
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadowCm = org.robolectric.Shadows.shadowOf(cm)
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            shadowCm.setNetworkCapabilities(activeNet, unvalidatedCaps)
            assertEquals(NetworkStatus.UNAVAILABLE, monitor.refresh())
            val beforeChanges = routeChanges
            shadowCm.setNetworkCapabilities(activeNet, unmeteredCaps)
            assertEquals(NetworkStatus.VALIDATED_UNMETERED, monitor.refresh())
            assertTrue("Expected route change callback when offline -> online", routeChanges > beforeChanges)
        }
    }

    @Test
    fun appContainer_onAppForegrounded_refreshesAndDebouncesForegroundEpoch() {
        val container = com.cloudyte.app.AppContainer(context)
        val baseEpoch = container.foregroundEpoch.value

        container.onAppForegrounded(nowMs = 10_000L)
        assertEquals(baseEpoch + 1L, container.foregroundEpoch.value)

        // Within 3s cooldown window -> should not re-emit foregroundEpoch
        container.onAppForegrounded(nowMs = 11_500L)
        assertEquals(baseEpoch + 1L, container.foregroundEpoch.value)

        // Past 3s cooldown window -> emits next foregroundEpoch
        container.onAppForegrounded(nowMs = 14_000L)
        assertEquals(baseEpoch + 2L, container.foregroundEpoch.value)
    }

    @Test
    fun homeViewModel_refreshIfNeeded_respectsOfflineStalenessAndRecovery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var accountsCalls = 0
        val fakeApi = object : com.cloudyte.app.api.CloudyteApi by createNoopApiProxy() {
            override suspend fun getProfile(): com.cloudyte.app.api.UserProfile =
                com.cloudyte.app.api.UserProfile(username = "tester")

            override suspend fun getAccounts(): List<com.cloudyte.app.api.Account> {
                accountsCalls++
                return listOf(
                    com.cloudyte.app.api.Account(
                        id = "acc-home-1",
                        name = "Main Drive",
                        driveName = "Main Drive",
                        type = "cloudyte",
                        usage = 100L,
                        total = 1000L,
                        isDefault = true
                    )
                )
            }

            override suspend fun getStorageSummary(accountId: String?): com.cloudyte.app.api.StorageSummary =
                com.cloudyte.app.api.StorageSummary(
                    accountId = accountId ?: "acc-home-1",
                    accountType = "cloudyte",
                    usedBytes = 100L,
                    totalBytes = 1000L,
                    fileCount = 0
                )

            override suspend fun getFiles(
                accountId: String?,
                folderId: String?,
                search: String?,
                cursor: String?,
                limit: Int?,
                ifNoneMatch: String?
            ): retrofit2.Response<List<com.cloudyte.app.api.CloudFile>> =
                retrofit2.Response.success(emptyList())
        }
        val repo = com.cloudyte.app.data.CloudFileRepository(db, fakeApi)
        val vm = com.cloudyte.app.ui.screens.HomeViewModel(
            application = context as android.app.Application,
            db = db,
            api = fakeApi,
            repository = repo
        )

        try {
            // 1. Offline -> skipped
            vm.refreshIfNeeded(isOnline = false, wasOffline = false, nowMs = 50_000L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(50)
            assertEquals(0, accountsCalls)

            // 2. First online refresh when never loaded -> runs
            vm.refreshIfNeeded(isOnline = true, wasOffline = false, nowMs = 50_000L)
            for (i in 0..10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(30)
                if (accountsCalls >= 1 && !vm.isLoading.value) break
            }
            assertEquals(1, accountsCalls)

            // Simulate a known recent load timestamp for deterministic staleness checks
            vm.resetLoadTimestampForTest(100_000L)

            // 3. Fresh (< 30s) and wasn't offline -> skipped
            vm.refreshIfNeeded(isOnline = true, wasOffline = false, nowMs = 110_000L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(50)
            assertEquals(1, accountsCalls)

            // 4. Stale (>= 30s) -> refreshes automatically
            vm.refreshIfNeeded(isOnline = true, wasOffline = false, nowMs = 131_000L)
            for (i in 0..10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(30)
                if (accountsCalls >= 2 && !vm.isLoading.value) break
            }
            assertEquals(2, accountsCalls)

            // 5. Even if freshly loaded, coming back from offline (wasOffline = true) forces refresh
            vm.resetLoadTimestampForTest(200_000L)
            vm.refreshIfNeeded(isOnline = true, wasOffline = true, nowMs = 201_000L)
            for (i in 0..10) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(30)
                if (accountsCalls >= 3 && !vm.isLoading.value) break
            }
            assertEquals(3, accountsCalls)
        } finally {
            db.close()
        }
    }

    private fun okioStreamSource(stream: java.io.InputStream): okio.Source = object : okio.Source {
        override fun read(sink: okio.Buffer, byteCount: Long): Long {
            val buf = ByteArray(minOf(byteCount, 8192L).toInt())
            val n = stream.read(buf, 0, buf.size)
            if (n < 0) return -1L
            sink.write(buf, 0, n)
            return n.toLong()
        }
        override fun timeout(): okio.Timeout = okio.Timeout.NONE
        override fun close() = stream.close()
    }

    private fun createNoopApiProxy(): com.cloudyte.app.api.CloudyteApi {
        return java.lang.reflect.Proxy.newProxyInstance(
            com.cloudyte.app.api.CloudyteApi::class.java.classLoader,
            arrayOf(com.cloudyte.app.api.CloudyteApi::class.java)
        ) { _, method, _ ->
            error("Unexpected call to ${method.name}")
        } as com.cloudyte.app.api.CloudyteApi
    }
}



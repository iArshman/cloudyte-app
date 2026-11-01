package com.cloudyte.app.core.transfer

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.LoginRequest
import com.cloudyte.app.api.LoginResponse
import com.cloudyte.app.api.MoveCopyRequest
import com.cloudyte.app.api.OAuthLinkResponse
import com.cloudyte.app.api.PublicSettingsResponse
import com.cloudyte.app.api.RegisterRequest
import com.cloudyte.app.api.RenameRequest
import com.cloudyte.app.api.ShareRequest
import com.cloudyte.app.api.ShareResponse
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.TgDriveRegisterRequest
import com.cloudyte.app.api.UploadChunkResponse
import com.cloudyte.app.api.UploadCompleteResponse
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.api.UploadSessionResponse
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.api.UserSettingsRequest
import com.cloudyte.app.core.cache.PreviewStore
import com.cloudyte.app.core.cache.ThumbnailStore
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.data.providers.ProviderRegistry
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDirection
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class ResumableTransferTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun checksumIsDeterministic() {
        val file = File.createTempFile("cloudyte", ".bin")
        try {
            file.writeText("cloudyte-transfer")
            assertEquals(
                "feb5165ecd9b8f6bbce5c7a0193ac7d76ec669debb117fbd7c56f9ea69ac93a7",
                ResumableTransfer.sha256(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun idempotencyKeyIsStableForTransfer() {
        assertEquals("cloudyte-transfer-123", ResumableTransfer.newIdempotencyKey("transfer-123"))
        assertEquals(
            ResumableTransfer.newIdempotencyKey("transfer-123"),
            ResumableTransfer.newSession("transfer-123", 100L).idempotencyKey
        )
    }

    @Test
    fun retryPolicyUsesBoundedExponentialBackoff() {
        val policy = RetryPolicy(maxAttempts = 3)
        assertTrue(policy.delayForAttempt(2) > policy.delayForAttempt(1))
        assertTrue(policy.canRetry(1))
        assertTrue(!policy.canRetry(3))
    }

    @Test
    fun unexpectedRuntimeAndSocketExceptionsDefaultToRetryableWhileExplicit404AndMissingSourceArePermanent(): Unit = runBlocking {
        database.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = "acc-1",
                    name = "Cloudyte",
                    driveName = "Main",
                    email = "u@cloudyte.io",
                    accountType = "cloudyte",
                    usageBytes = 0L,
                    totalBytes = 1000L,
                    percentage = 0f,
                    fileCount = 0,
                    isDefault = true,
                    channelId = null
                )
            )
        )

        var failureToThrow: Throwable? = RuntimeException("Unexpected JSON glitch")
        var statusCodeToReturn: Int = 200

        val api = object : BaseFakeApi() {
            override suspend fun downloadFile(
                accountId: String,
                fileId: String,
                preview: Int?,
                range: String?
            ): Response<ResponseBody> {
                failureToThrow?.let { throw it }
                return Response.error(
                    statusCodeToReturn,
                    "error".toResponseBody("text/plain".toMediaType())
                )
            }
        }

        val registry = ProviderRegistry(api)
        val executor = CloudTransferOperationExecutor(
            context = context,
            database = database,
            transfers = TransferRepository(database.transferDao(), registry),
            providers = registry
        )

        val destFile = File(context.cacheDir, "err_test_dest.bin")
        destFile.delete()
        val downloadRow = LocalTransfer(
            id = "dl-err-1",
            accountId = "acc-1",
            fileId = "file-1",
            remoteId = "file-1",
            fileName = "err_test_dest.bin",
            localPath = destFile.absolutePath,
            direction = TransferDirection.DOWNLOAD.name,
            operationType = TransferDirection.DOWNLOAD.name,
            providerType = "cloudyte",
            leaseOwner = "owner-1"
        )
        database.transferDao().insert(downloadRow)

        // 1. Unexpected RuntimeException -> Retryable
        val runtimeOutcome = executor.execute(downloadRow, "owner-1") { _, _, _, _ -> }
        assertTrue("Unexpected RuntimeException should be Retryable", runtimeOutcome is TransferOutcome.Retryable)

        // 2. SocketException -> Retryable
        failureToThrow = SocketException("Connection reset")
        val socketOutcome = executor.execute(downloadRow, "owner-1") { _, _, _, _ -> }
        assertTrue("SocketException should be Retryable", socketOutcome is TransferOutcome.Retryable)

        // 3. Explicit HTTP 404 -> Permanent
        failureToThrow = null
        statusCodeToReturn = 404
        val notFoundOutcome = executor.execute(downloadRow, "owner-1") { _, _, _, _ -> }
        assertTrue("HTTP 404 should be Permanent", notFoundOutcome is TransferOutcome.Permanent)

        // 4. Missing local source file on upload -> Permanent
        val missingUploadRow = LocalTransfer(
            id = "ul-missing-1",
            accountId = "acc-1",
            fileName = "missing.bin",
            localPath = File(context.cacheDir, "does_not_exist_12345.bin").absolutePath,
            direction = TransferDirection.UPLOAD.name,
            operationType = TransferDirection.UPLOAD.name,
            providerType = "cloudyte",
            leaseOwner = "owner-1"
        )
        database.transferDao().insert(missingUploadRow)
        val missingSourceOutcome = executor.execute(missingUploadRow, "owner-1") { _, _, _, _ -> }
        assertTrue("Missing local source file should be Permanent", missingSourceOutcome is TransferOutcome.Permanent)
    }

    @Test
    fun resumableDownloadWritesPartFileSendsRangeHeaderAndAtomicallyRenamesOnCompletion(): Unit = runBlocking {
        database.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = "acc-dl",
                    name = "Cloudyte",
                    driveName = "Main",
                    email = "u@cloudyte.io",
                    accountType = "cloudyte",
                    usageBytes = 0L,
                    totalBytes = 1000L,
                    percentage = 0f,
                    fileCount = 0,
                    isDefault = true,
                    channelId = null
                )
            )
        )

        val fullPayload = "0123456789ABCDEF".toByteArray()
        val destFile = File(context.cacheDir, "resumable_target.bin")
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        destFile.delete()
        partFile.delete()

        // Pre-populate .part file with first 6 bytes ("012345") to simulate interrupted previous attempt
        partFile.writeBytes(fullPayload.copyOfRange(0, 6))

        val capturedRanges = mutableListOf<String?>()
        val api = object : BaseFakeApi() {
            override suspend fun downloadFile(
                accountId: String,
                fileId: String,
                preview: Int?,
                range: String?
            ): Response<ResponseBody> {
                capturedRanges += range
                val remaining = fullPayload.copyOfRange(6, fullPayload.size)
                val rawResponse = okhttp3.Response.Builder()
                    .request(Request.Builder().url("https://cloudyte.local/download").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .headers(
                        Headers.headersOf(
                            "Content-Range", "bytes 6-15/16",
                            "Content-Length", remaining.size.toString()
                        )
                    )
                    .build()
                return Response.success(
                    remaining.toResponseBody("application/octet-stream".toMediaType()),
                    rawResponse
                )
            }
        }

        val registry = ProviderRegistry(api)
        val executor = CloudTransferOperationExecutor(
            context = context,
            database = database,
            transfers = TransferRepository(database.transferDao(), registry),
            providers = registry
        )

        val transfer = LocalTransfer(
            id = "dl-resume-1",
            accountId = "acc-dl",
            fileId = "remote-99",
            remoteId = "remote-99",
            fileName = destFile.name,
            localPath = destFile.absolutePath,
            direction = TransferDirection.DOWNLOAD.name,
            operationType = TransferDirection.DOWNLOAD.name,
            providerType = "cloudyte",
            status = "RUNNING",
            state = TransferState.RUNNING.name,
            totalBytes = fullPayload.size.toLong(),
            bytesTransferred = 6L,
            leaseOwner = "owner-dl",
            leaseExpiresAt = System.currentTimeMillis() + 120_000L
        )
        database.transferDao().insert(transfer)

        val outcome = executor.execute(transfer, "owner-dl") { _, _, _, _ -> }
        assertTrue(outcome is TransferOutcome.Completed)
        assertEquals(listOf("bytes=6-"), capturedRanges)
        assertFalse(".part file should be renamed after completion", partFile.exists())
        assertTrue("Final destination file should exist", destFile.exists())
        assertEquals("0123456789ABCDEF", destFile.readText())
        destFile.delete()
    }

    @Test
    fun previewStoreGetTouchesAccessTimestampForTrueLruEviction(): Unit = runBlocking {
        val lruStore = PreviewStore(context)
        try {
            lruStore.clear()
            lruStore.put("item_a", "AAAAAAAAAA".toByteArray()) // 10 bytes
            delay(15L)
            lruStore.put("item_b", "BBBBBBBBBB".toByteArray()) // 10 bytes
            delay(15L)

            // Touch item_a via get() so item_a is now more recently accessed than item_b
            assertNotNull(lruStore.get("item_a"))
            delay(15L)

            lruStore.put("item_c", "CCCCCCCCCC".toByteArray()) // 10 bytes (total 30 bytes)

            // Enforce quota of 25 bytes -> item_b (least recently accessed) must be evicted while item_a and item_c survive!
            lruStore.enforceQuota(25L)

            assertNotNull("Frequently accessed item_a must survive LRU eviction", lruStore.get("item_a"))
            assertNull("Least recently accessed item_b must be evicted", lruStore.get("item_b"))
            assertNotNull("Newly added item_c must exist", lruStore.get("item_c"))
        } finally {
            lruStore.clear()
        }
    }

    @Test
    fun thumbnailStoreDeduplicatesPerKeyAndGeneratesFromLocalFile(): Unit = runBlocking {
        val localImageFile = File(context.cacheDir, "sample_local_thumb.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.BLUE)
        FileOutputStream(localImageFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val store = ThumbnailStore(
            context = context,
            dao = database.thumbnailDao()
        )

        try {
            val file = CloudFile(
                id = "local-img-1",
                name = "photo.png",
                type = "image",
                mimeType = "image/png"
            )
            // Concurrent requests for the same key and local file deduplicate and resolve to the generated cached file
            val d1 = async {
                store.resolve(
                    accountId = "acc-1",
                    providerType = "cloudyte",
                    file = file,
                    sizePx = 128,
                    localSource = localImageFile
                )
            }
            val d2 = async {
                store.resolve(
                    accountId = "acc-1",
                    providerType = "cloudyte",
                    file = file,
                    sizePx = 128,
                    localSource = localImageFile
                )
            }
            val results = awaitAll(d1, d2)
            assertNotNull(results[0])
            assertEquals(results[0], results[1])
            assertTrue(File(results[0]!!).isFile)
        } finally {
            localImageFile.delete()
        }
    }

    private open class BaseFakeApi : CloudyteApi {
        override suspend fun getPublicSettings(): PublicSettingsResponse = PublicSettingsResponse()
        override suspend fun register(request: RegisterRequest): Response<ResponseBody> =
            Response.success("{}".toResponseBody("application/json".toMediaType()))
        override suspend fun login(request: LoginRequest): Response<LoginResponse> =
            Response.success(LoginResponse(token = "t"))
        override suspend fun logout(): Response<Unit> = Response.success(Unit)
        override suspend fun getProfile(): UserProfile = UserProfile(id = "u", username = "u")
        override suspend fun updateSettings(request: UserSettingsRequest): Response<Unit> = Response.success(Unit)
        override suspend fun getAccounts(): List<Account> = emptyList()
        override suspend fun deleteAccount(id: String): Response<Unit> = Response.success(Unit)
        override suspend fun getGoogleDriveLink(): OAuthLinkResponse = OAuthLinkResponse()
        override suspend fun getOneDriveLink(): OAuthLinkResponse = OAuthLinkResponse()
        override suspend fun registerTgDrive(request: TgDriveRegisterRequest): Response<Unit> = Response.success(Unit)
        override suspend fun getFiles(
            accountId: String?,
            folderId: String?,
            search: String?,
            cursor: String?,
            limit: Int?,
            ifNoneMatch: String?
        ): Response<List<CloudFile>> = Response.success(emptyList())
        override suspend fun createFolder(accountId: String?, request: FolderRequest): Response<Unit> =
            Response.success(Unit)
        override suspend fun uploadFile(
            accountId: String,
            folderIdQuery: String?,
            file: MultipartBody.Part,
            folderIdPart: RequestBody?,
            parentIdPart: RequestBody?,
            idempotencyKey: String?,
            checksum: String?,
            uploadSession: String?
        ): Response<ResponseBody> = Response.success("{}".toResponseBody("application/json".toMediaType()))
        override suspend fun createUploadSession(
            accountId: String,
            request: UploadSessionRequest
        ): Response<UploadSessionResponse> = Response.success(
            UploadSessionResponse(sessionId = "s1", status = "active", totalBytes = request.size)
        )
        override suspend fun getUploadSession(sessionId: String): Response<UploadSessionResponse> =
            Response.success(UploadSessionResponse(sessionId = sessionId, status = "active", totalBytes = 0L))
        override suspend fun uploadChunk(
            sessionId: String,
            contentRange: String,
            chunk: MultipartBody.Part
        ): Response<UploadChunkResponse> = Response.success(
            UploadChunkResponse(sessionId = sessionId, receivedBytes = 0L, totalBytes = 0L, nextOffset = 0L)
        )
        override suspend fun completeUploadSession(sessionId: String): Response<UploadCompleteResponse> =
            Response.success(UploadCompleteResponse(success = true, sessionId = sessionId))
        override suspend fun cancelUploadSession(sessionId: String): Response<Unit> =
            Response.success(Unit)
        override suspend fun downloadFile(
            accountId: String,
            fileId: String,
            preview: Int?,
            range: String?
        ): Response<ResponseBody> = Response.success("".toResponseBody("application/octet-stream".toMediaType()))
        override suspend fun renameFile(
            fileId: String,
            accountId: String?,
            request: RenameRequest
        ): Response<Unit> = Response.success(Unit)
        override suspend fun deleteFile(fileId: String, accountId: String?): Response<Unit> =
            Response.success(Unit)
        override suspend fun moveFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
        override suspend fun copyFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
        override suspend fun shareFile(request: ShareRequest): ShareResponse = ShareResponse()
        override suspend fun getStorageSummary(accountId: String?): StorageSummary =
            StorageSummary(accountId = accountId ?: "all", accountType = "cloudyte", usedBytes = 0L)
    }
}

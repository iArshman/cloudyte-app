package com.cloudyte.app.data

import android.content.Context
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
import com.cloudyte.app.api.StorageCategories
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.TgDriveRegisterRequest
import com.cloudyte.app.api.UploadChunkResponse
import com.cloudyte.app.api.UploadCompleteResponse
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.api.UploadSessionResponse
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.api.UserSettingsRequest
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalFileMutation
import com.cloudyte.app.db.LocalStorageSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.Executor

class FakeCloudyteApi : CloudyteApi {
    var getFilesCallCount: Int = 0
    val getFilesCalls = mutableListOf<Triple<String?, String?, String?>>() // (accountId, folderId, ifNoneMatch)

    var getFilesHandler: suspend (
        accountId: String?,
        folderId: String?,
        search: String?,
        cursor: String?,
        limit: Int?,
        ifNoneMatch: String?
    ) -> Response<List<CloudFile>> = { _, _, _, _, _, _ ->
        Response.success(emptyList())
    }

    val renameCalls = mutableListOf<Triple<String, String?, String>>()
    var renameHandler: suspend (
        fileId: String,
        accountId: String?,
        request: RenameRequest
    ) -> Response<Unit> = { _, _, _ ->
        Response.success(Unit)
    }

    val deleteCalls = mutableListOf<Pair<String, String?>>()
    var deleteHandler: suspend (
        fileId: String,
        accountId: String?
    ) -> Response<Unit> = { _, _ ->
        Response.success(Unit)
    }

    val createFolderCalls = mutableListOf<Pair<String?, FolderRequest>>()
    var createFolderHandler: suspend (
        accountId: String?,
        request: FolderRequest
    ) -> Response<Unit> = { _, _ ->
        Response.success(Unit)
    }

    var accountsList: List<Account> = emptyList()

    override suspend fun getPublicSettings(): PublicSettingsResponse = PublicSettingsResponse()
    override suspend fun register(request: RegisterRequest): Response<ResponseBody> =
        Response.success("{}".toResponseBody("application/json".toMediaType()))
    override suspend fun login(request: LoginRequest): Response<LoginResponse> =
        Response.success(LoginResponse(token = "test"))
    override suspend fun logout(): Response<Unit> = Response.success(Unit)
    override suspend fun getProfile(): UserProfile = UserProfile(id = "u1", username = "tester")
    override suspend fun updateSettings(request: UserSettingsRequest): Response<Unit> = Response.success(Unit)
    override suspend fun putEncryptionKeyBackup(accountId: String, backup: com.cloudyte.app.api.EncryptionKeyBackup): Response<Unit> = Response.success(Unit)
    override suspend fun getEncryptionKeyBackup(accountId: String): Response<com.cloudyte.app.api.EncryptionKeyBackup> =
        Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
    override suspend fun deleteEncryptionKeyBackup(accountId: String): Response<Unit> = Response.success(Unit)
    override suspend fun getAccounts(): List<Account> = accountsList
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
    ): Response<List<CloudFile>> {
        getFilesCallCount++
        getFilesCalls.add(Triple(accountId, folderId, ifNoneMatch))
        return getFilesHandler(accountId, folderId, search, cursor, limit, ifNoneMatch)
    }

    override suspend fun createFolder(accountId: String?, request: FolderRequest): Response<Unit> {
        createFolderCalls.add(accountId to request)
        return createFolderHandler(accountId, request)
    }

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
        UploadSessionResponse(sessionId = "test-session", status = "active", totalBytes = request.size)
    )

    override suspend fun getUploadSession(sessionId: String): Response<UploadSessionResponse> = Response.success(
        UploadSessionResponse(sessionId = sessionId, status = "active", totalBytes = 0L)
    )

    override suspend fun uploadChunk(
        sessionId: String,
        contentRange: String,
        chunk: MultipartBody.Part
    ): Response<UploadChunkResponse> = Response.success(
        UploadChunkResponse(sessionId = sessionId, receivedBytes = 0L, totalBytes = 0L, nextOffset = 0L)
    )

    override suspend fun uploadChunkStream(
        sessionId: String,
        contentRange: String,
        chunkOffset: Long,
        chunk: RequestBody
    ): Response<UploadChunkResponse> = uploadChunk(
        sessionId = sessionId,
        contentRange = contentRange,
        chunk = MultipartBody.Part.createFormData("chunk", "chunk.bin", chunk)
    )

    override suspend fun pauseUploadSession(sessionId: String): Response<UploadSessionResponse> = Response.success(
        UploadSessionResponse(sessionId = sessionId, status = "paused", totalBytes = 0L)
    )

    override suspend fun resumeUploadSession(sessionId: String): Response<UploadSessionResponse> = Response.success(
        UploadSessionResponse(sessionId = sessionId, status = "active", totalBytes = 0L)
    )

    override suspend fun completeUploadSession(sessionId: String): Response<UploadCompleteResponse> = Response.success(
        UploadCompleteResponse(success = true, sessionId = sessionId)
    )

    override suspend fun cancelUploadSession(sessionId: String): Response<Unit> = Response.success(Unit)

    override suspend fun getMediaLink(accountId: String, fileId: String): Response<com.cloudyte.app.api.MediaLinkResponse> =
        Response.success(com.cloudyte.app.api.MediaLinkResponse(url = "/api/download/$accountId/$fileId?preview=1", expiresAt = System.currentTimeMillis() + 600_000L))

    override suspend fun downloadFile(
        accountId: String,
        fileId: String,
        preview: Int?,
        range: String?
    ): Response<ResponseBody> =
        Response.success("".toResponseBody("application/octet-stream".toMediaType()))

    override suspend fun renameFile(
        fileId: String,
        accountId: String?,
        request: RenameRequest
    ): Response<Unit> {
        renameCalls.add(Triple(fileId, accountId, request.name))
        return renameHandler(fileId, accountId, request)
    }

    override suspend fun deleteFile(fileId: String, accountId: String?): Response<Unit> {
        deleteCalls.add(fileId to accountId)
        return deleteHandler(fileId, accountId)
    }

    override suspend fun moveFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
    override suspend fun copyFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
    override suspend fun shareFile(request: ShareRequest): ShareResponse = ShareResponse()

    var storageSummaryHandler: suspend (accountId: String?) -> StorageSummary = { accountId ->
        StorageSummary(
            accountId = accountId ?: "all",
            accountType = "gdrive",
            usedBytes = 100L,
            totalBytes = 1000L,
            fileCount = 5,
            categories = StorageCategories(images = 60L, documents = 40L)
        )
    }

    var getStorageSummaryHandler: suspend (accountId: String?) -> StorageSummary
        get() = storageSummaryHandler
        set(value) {
            storageSummaryHandler = value
        }

    override suspend fun getStorageSummary(accountId: String?): StorageSummary {
        return storageSummaryHandler(accountId)
    }

    override suspend fun moveFileAsync(request: MoveCopyRequest, async: Boolean): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true))

    override suspend fun copyFileAsync(request: MoveCopyRequest, async: Boolean): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true))

    override suspend fun getJobs(status: String?): Response<List<com.cloudyte.app.api.JobStatusResponse>> =
        Response.success(emptyList())

    override suspend fun getJobStatus(jobId: String): Response<com.cloudyte.app.api.JobStatusResponse> =
        Response.success(com.cloudyte.app.api.JobStatusResponse(id = jobId, status = "completed", progressPct = 100))

    override suspend fun cancelJob(jobId: String): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true, jobId = jobId, status = "cancelled"))

    override suspend fun retryJob(jobId: String): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true, jobId = jobId, status = "queued"))

    override suspend fun createBatchBackup(request: com.cloudyte.app.api.BatchBackupRequest): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true, async = true, jobId = "job-backup-1", status = "queued"))

    override suspend fun verifyBackupJob(jobId: String): Response<com.cloudyte.app.api.BackupVerifyResponse> =
        Response.success(com.cloudyte.app.api.BackupVerifyResponse(jobId = jobId, verified = true))

    override suspend fun restoreBackupJob(jobId: String, request: com.cloudyte.app.api.BackupRestoreRequest): Response<com.cloudyte.app.api.TransferActionResponse> =
        Response.success(com.cloudyte.app.api.TransferActionResponse(success = true, async = true, jobId = "job-restore-1", status = "queued"))

    override suspend fun getTrash(accountId: String?): List<CloudFile> = emptyList()
    override suspend fun restoreFromTrash(request: com.cloudyte.app.api.TrashRestoreRequest): Response<Unit> = Response.success(Unit)
    override suspend fun deleteFromTrash(fileId: String, accountId: String): Response<Unit> = Response.success(Unit)
    override suspend fun emptyTrash(accountId: String?): Response<Unit> = Response.success(Unit)
    override suspend fun getSessions(): List<com.cloudyte.app.api.ActiveSessionDto> = emptyList()
    override suspend fun revokeSession(sessionId: String): Response<Unit> = Response.success(Unit)
    override suspend fun revokeOtherSessions(): Response<Unit> = Response.success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudFileRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var fakeApi: FakeCloudyteApi
    private lateinit var repository: CloudFileRepository
    private var currentClockMs: Long = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        fakeApi = FakeCloudyteApi()
        currentClockMs = 1_000_000L
        repository = CloudFileRepository(db, fakeApi, clock = { currentClockMs })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun coldStart_fetchesAndPersistsFilesAndSyncMeta() = runTest {
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(CloudFile(id = "f1", name = "ColdStart.pdf", type = "pdf")),
                Headers.headersOf("ETag", "\"etag-cold-1\"")
            )
        }

        val res = repository.refresh("acc1", "root", force = false)
        assertTrue(res.isSuccess)
        assertEquals(1, fakeApi.getFilesCallCount)

        val savedFiles = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(1, savedFiles.size)
        assertEquals("ColdStart.pdf", savedFiles.first().name)

        val meta = db.folderSyncMetaDao().getMeta("acc1", "root")
        assertEquals("\"etag-cold-1\"", meta?.etag)
        assertTrue("Folder should be fresh immediately after sync", repository.isFolderFresh("acc1", "root"))
    }

    @Test
    fun warmCacheWithinFreshnessWindow_skipsNetworkRequestWhenForceIsFalse() = runTest {
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(CloudFile(id = "f1", name = "WarmFile.txt", type = "file")),
                Headers.headersOf("ETag", "\"etag-1\"")
            )
        }

        // First refresh populates Room and folder_sync_meta
        assertTrue(repository.refresh("acc1", "root", force = false).isSuccess)
        assertEquals(1, fakeApi.getFilesCallCount)

        // Advance time by 15s (well within 60s TTL)
        currentClockMs += 15_000L

        // Second refresh with force = false must skip the network call
        assertTrue(repository.refresh("acc1", "root", force = false).isSuccess)
        assertEquals("Expected zero additional network calls when cache is fresh", 1, fakeApi.getFilesCallCount)

        // Even a brand new CloudFileRepository instance (simulating navigating back to FilesScreen)
        // reads persisted folder_sync_meta from Room and skips the network call!
        val secondRepoInstance = CloudFileRepository(db, fakeApi, clock = { currentClockMs })
        assertTrue(secondRepoInstance.refresh("acc1", "root", force = false).isSuccess)
        assertEquals("New repository instance must also skip network call when Room cache is fresh", 1, fakeApi.getFilesCallCount)
    }

    @Test
    fun expiredCacheAfterTtl_performsConditionalRequestWithPersistedEtagAndHandles304() = runTest {
        fakeApi.getFilesHandler = { _, _, _, _, _, ifNoneMatch ->
            if (ifNoneMatch == "\"etag-v1\"") {
                val raw304 = okhttp3.Response.Builder()
                    .request(okhttp3.Request.Builder().url("https://cloudyte.local/api/files").build())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(304)
                    .message("Not Modified")
                    .addHeader("ETag", "\"etag-v1\"")
                    .build()
                Response.error("".toResponseBody(null), raw304)
            } else {
                Response.success(
                    listOf(CloudFile(id = "f1", name = "CachedDoc.pdf", type = "pdf")),
                    Headers.headersOf("ETag", "\"etag-v1\"")
                )
            }
        }

        // Initial load
        assertTrue(repository.refresh("acc1", "root", force = false).isSuccess)
        assertEquals(1, fakeApi.getFilesCallCount)
        assertEquals(null, fakeApi.getFilesCalls.last().third)

        // Advance clock past 60s TTL
        currentClockMs += 65_000L
        assertFalse(repository.isFolderFresh("acc1", "root"))

        // Next refresh sends If-None-Match: "etag-v1" and receives 304 Not Modified
        assertTrue(repository.refresh("acc1", "root", force = false).isSuccess)
        assertEquals(2, fakeApi.getFilesCallCount)
        assertEquals("\"etag-v1\"", fakeApi.getFilesCalls.last().third)

        // Cached files are preserved and folder is marked fresh again
        val filesInDb = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(1, filesInDb.size)
        assertEquals("CachedDoc.pdf", filesInDb.first().name)
        assertTrue("304 response must refresh lastSyncedAt so folder is fresh again", repository.isFolderFresh("acc1", "root"))
    }

    @Test
    fun forcedRefresh_bypassesTtlCheckAndUpdatesRoom() = runTest {
        var version = 1
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(CloudFile(id = "f1", name = "Doc_v$version.pdf", type = "pdf")),
                Headers.headersOf("ETag", "\"etag-v$version\"")
            )
        }

        assertTrue(repository.refresh("acc1", "root", force = false).isSuccess)
        assertEquals(1, fakeApi.getFilesCallCount)
        assertEquals("Doc_v1.pdf", db.cloudFileDao().getFilesForFolder("acc1", "root").first().name)

        // Still within TTL, but force = true must hit the network
        version = 2
        assertTrue(repository.refresh("acc1", "root", force = true).isSuccess)
        assertEquals(2, fakeApi.getFilesCallCount)
        assertEquals("Doc_v2.pdf", db.cloudFileDao().getFilesForFolder("acc1", "root").first().name)
    }

    @Test
    fun multiPageFailureOnPage2_preservesExistingRoomDataUntouched() = runTest {
        // Seed existing valid folder state
        val existing = CloudFile(id = "existing_1", name = "SafeExistingFile.jpg", type = "image")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(existing, "acc1", "root")))

        fakeApi.getFilesHandler = { _, _, _, cursor, _, _ ->
            if (cursor == null) {
                Response.success(
                    listOf(CloudFile(id = "partial_p1", name = "PartialPage1.jpg", type = "image")),
                    Headers.headersOf("X-Next-Cursor", "cursor_page_2")
                )
            } else {
                Response.error(502, """{"error":"Bad Gateway on page 2"}""".toResponseBody("application/json".toMediaType()))
            }
        }

        val result = repository.refresh("acc1", "root", force = true)
        assertTrue(result.isFailure)

        // Existing Room rows must remain intact and NOT be polluted by partial page 1 rows
        val rowsInRoom = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(1, rowsInRoom.size)
        assertEquals("existing_1", rowsInRoom.first().id)
        assertEquals("SafeExistingFile.jpg", rowsInRoom.first().name)
    }

    @Test
    fun concurrentRefreshesForSameFolder_deduplicateIntoSingleNetworkRequest() = runTest {
        val gate = CompletableDeferred<Response<List<CloudFile>>>()
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            gate.await()
        }

        val deferred1 = async { repository.refresh("acc1", "root", force = false) }
        val deferred2 = async { repository.refresh("acc1", "root", force = false) }
        testScheduler.runCurrent()

        gate.complete(
            Response.success(
                listOf(CloudFile(id = "shared_1", name = "SharedResult.txt", type = "file")),
                Headers.headersOf("ETag", "\"etag-shared\"")
            )
        )

        val res1 = deferred1.await()
        val res2 = deferred2.await()
        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals("Concurrent non-forced refreshes for the same folder should share 1 API call", 1, fakeApi.getFilesCallCount)
    }

    @Test
    fun rename_onlineCallsApiImmediatelyWithoutQueuingOutbox() = runTest {
        val original = CloudFile(id = "file_online_1", name = "Draft.pdf", type = "pdf")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        val result = repository.rename("file_online_1", "acc1", "Final.pdf")

        assertTrue(result.isSuccess)
        assertEquals(listOf(Triple("file_online_1", "acc1", "Final.pdf")), fakeApi.renameCalls)
        assertEquals("Final.pdf", db.cloudFileDao().getFileById("file_online_1")?.name)
        assertTrue("Online rename should not enqueue outbox item", db.fileMutationDao().pending().isEmpty())
    }

    @Test
    fun createFolder_onlineCallsApiAndRefreshesFolderImmediately() = runTest {
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(CloudFile(id = "srv_folder_1", name = "Work", type = "folder", isFolder = true))
            )
        }

        val result = repository.createFolder("Work", "acc1", "root")

        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.createFolderCalls.size)
        assertEquals("Work", fakeApi.createFolderCalls.single().second.name)
        assertTrue("Online createFolder should not enqueue outbox item", db.fileMutationDao().pending().isEmpty())
        val stored = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(listOf("srv_folder_1"), stored.map { it.id })
    }

    @Test
    fun createFolder_and_rename_propagateServerHttpErrorWithoutQueuingOfflineOutbox() = runTest {
        val original = CloudFile(id = "file_err", name = "Doc.txt", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        fakeApi.createFolderHandler = { _, _ ->
            Response.error(403, """{"error":"Permission denied by provider"}""".toResponseBody("application/json".toMediaType()))
        }
        fakeApi.renameHandler = { _, _, _ ->
            Response.error(400, """{"error":"Invalid filename character"}""".toResponseBody("application/json".toMediaType()))
        }

        val folderErr = repository.createFolder("Bad/", "acc1", "root")
        val renameErr = repository.rename("file_err", "acc1", "Bad?.txt")

        assertTrue(folderErr.isFailure)
        assertEquals("Permission denied by provider", folderErr.exceptionOrNull()?.message)
        assertTrue(renameErr.isFailure)
        assertEquals("Invalid filename character", renameErr.exceptionOrNull()?.message)
        assertEquals("Doc.txt", db.cloudFileDao().getFileById("file_err")?.name)
        assertTrue("HTTP errors must not enqueue offline outbox mutations", db.fileMutationDao().pending().isEmpty())
    }

    @Test
    fun rename_isOptimisticAndPersistedInOutboxWithoutNetwork() = runTest {
        fakeApi.renameHandler = { _, _, _ -> throw IOException("Network unavailable") }
        val original = CloudFile(id = "file_1", name = "OriginalReport.pdf", type = "pdf")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        val result = repository.rename("file_1", "acc1", "RenamedReport.pdf")

        assertTrue(result.isSuccess)
        assertEquals("RenamedReport.pdf", db.cloudFileDao().getFileById("file_1")?.name)
        val queued = db.fileMutationDao().pending().single()
        assertEquals(LocalFileMutation.RENAME, queued.operation)
        assertEquals("file_1", queued.fileId)
        assertEquals("RenamedReport.pdf", queued.name)
    }

    @Test
    fun queuedRenameSurvivesRepositoryRecreation() = runTest {
        fakeApi.renameHandler = { _, _, _ -> throw IOException("Network unavailable") }
        val original = CloudFile(id = "file_2", name = "Budget2026.xlsx", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        val result = repository.rename("file_2", "acc1", "CorruptedName.xlsx")

        assertTrue(result.isSuccess)
        val reopenedRepository = CloudFileRepository(db, fakeApi, clock = { currentClockMs })
        assertEquals("CorruptedName.xlsx", reopenedRepository.getFileById("file_2")?.effectiveName)
        assertEquals(1, db.fileMutationDao().pending().size)
    }

    @Test
    fun delete_removesImmediatelyAndPersistsOutboxEntry() = runTest {
        fakeApi.deleteHandler = { _, _ -> throw IOException("Network unavailable") }
        val original = CloudFile(id = "del_1", name = "Important.docx", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        val result = repository.delete("del_1", "acc1")
        assertTrue(result.isSuccess)
        assertEquals(null, db.cloudFileDao().getFileById("del_1"))
        assertEquals(LocalFileMutation.DELETE, db.fileMutationDao().pending().single().operation)
    }

    @Test
    fun createFolder_insertsOptimisticallyAndQueuesForReplay() = runTest {
        fakeApi.createFolderHandler = { _, _ -> throw IOException("Network unavailable") }
        val result = repository.createFolder("Projects", "acc1", "root")
        assertTrue(result.isSuccess)
        val folders = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(1, folders.size)
        assertEquals("Projects", folders.first().name)
        assertTrue(folders.first().id.startsWith("temp_"))
        val queued = db.fileMutationDao().pending().single()
        assertEquals(LocalFileMutation.CREATE_FOLDER, queued.operation)
        assertEquals(folders.first().id, queued.optimisticFileId)
    }

    @Test
    fun refresh_preservesQueuedRenameDeleteAndCreateFolderOverServerSnapshot() = runTest {
        fakeApi.renameHandler = { _, _, _ -> throw IOException("Network unavailable") }
        fakeApi.deleteHandler = { _, _ -> throw IOException("Network unavailable") }
        fakeApi.createFolderHandler = { _, _ -> throw IOException("Network unavailable") }
        db.cloudFileDao().insertFiles(
            listOf(
                LocalCloudFile.fromCloudFile(CloudFile(id = "keep", name = "Before.txt", type = "file"), "acc1", "root"),
                LocalCloudFile.fromCloudFile(CloudFile(id = "remove", name = "Remove.txt", type = "file"), "acc1", "root")
            )
        )
        assertTrue(repository.rename("keep", "acc1", "After.txt").isSuccess)
        assertTrue(repository.delete("remove", "acc1").isSuccess)
        assertTrue(repository.createFolder("Queued folder", "acc1", "root").isSuccess)
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(
                    CloudFile(id = "keep", name = "Before.txt", type = "file"),
                    CloudFile(id = "remove", name = "Remove.txt", type = "file")
                )
            )
        }

        assertTrue(repository.refresh("acc1", "root", force = true).isSuccess)

        val rows = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(setOf("After.txt", "Queued folder"), rows.map { it.name }.toSet())
        assertEquals("After.txt", rows.single { it.id == "keep" }.name)
        assertTrue(rows.single { it.name == "Queued folder" }.id.startsWith("temp_"))
    }

    @Test
    fun refresh_doesNotClearListWhileFetchingPages() = runTest {
        // Seed initial cached files in Room
        val cached1 = CloudFile(id = "f1", name = "Cached1.jpg", type = "image")
        val cached2 = CloudFile(id = "f2", name = "Cached2.jpg", type = "image")
        db.cloudFileDao().insertFiles(
            listOf(
                LocalCloudFile.fromCloudFile(cached1, "acc1", "root"),
                LocalCloudFile.fromCloudFile(cached2, "acc1", "root")
            )
        )

        val emissions = mutableListOf<List<CloudFile>>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeFolder("acc1", "root").toList(emissions)
        }

        // Simulate 2-page cursor response where f1 is kept, f2 is removed, and f3 + f4 are added
        fakeApi.getFilesHandler = { _, _, _, cursor, _, _ ->
            // Verify Room is still non-empty while fetching pages
            assertTrue(db.cloudFileDao().getFilesForFolder("acc1", "root").isNotEmpty())
            if (cursor == null) {
                val page1 = listOf(
                    CloudFile(id = "f1", name = "Cached1.jpg", type = "image"),
                    CloudFile(id = "f3", name = "NewPage1.jpg", type = "image")
                )
                Response.success(page1, Headers.headersOf("X-Next-Cursor", "page_2"))
            } else {
                val page2 = listOf(
                    CloudFile(id = "f4", name = "NewPage2.jpg", type = "image")
                )
                Response.success(page2)
            }
        }

        val refreshResult = repository.refresh("acc1", "root")
        assertTrue(refreshResult.isSuccess)

        // Verify the list was NEVER cleared to empty during refresh
        assertTrue(emissions.isNotEmpty())
        assertFalse("List must never be cleared during refresh", emissions.any { it.isEmpty() })

        // Verify final state has [f1, f3, f4] and stale f2 was removed
        val finalIds = db.cloudFileDao().getFilesForFolder("acc1", "root").map { it.id }.toSet()
        assertEquals(setOf("f1", "f3", "f4"), finalIds)

        collectJob.cancel()
    }

    @Test
    fun observeStorage_emitsSavedValueImmediatelyAndRefreshesAtMostOncePer60Seconds() = runTest {
        repository = CloudFileRepository(db, fakeApi, clock = { 100_000L + testScheduler.currentTime })
        val savedSummary = StorageSummary(
            accountId = "acc1",
            accountType = "gdrive",
            usedBytes = 500L,
            totalBytes = 10_000L,
            fileCount = 12,
            categories = StorageCategories(images = 300L, documents = 200L)
        )
        // Seed old updatedAt so the first background refresh executes
        db.storageSummaryDao().insert(
            LocalStorageSummary.fromStorageSummary(
                summary = savedSummary,
                fallbackAccountId = "acc1",
                updatedAt = 0L
            )
        )

        var apiCalls = 0
        fakeApi.getStorageSummaryHandler = { accountId ->
            apiCalls++
            StorageSummary(
                accountId = accountId ?: "acc1",
                accountType = "gdrive",
                usedBytes = 1500L + apiCalls,
                totalBytes = 10_000L,
                fileCount = 25,
                categories = StorageCategories(images = 1000L, documents = 500L + apiCalls)
            )
        }

        val emissions = mutableListOf<StorageSummary?>()
        val job1 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeStorage("acc1").toList(emissions)
        }
        advanceUntilIdle()

        // Emits cached value immediately, then refreshed value from background API call
        assertTrue(emissions.size >= 2)
        assertEquals(500L, emissions.first()?.usedBytes)
        assertEquals(1501L, emissions.last()?.usedBytes)
        assertEquals(1, apiCalls)
        job1.cancel()

        // Observing again within 60 seconds should NOT trigger another network call
        val secondEmissions = mutableListOf<StorageSummary?>()
        val job2 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeStorage("acc1").toList(secondEmissions)
        }
        advanceUntilIdle()
        assertEquals(1, apiCalls)
        assertEquals(1501L, secondEmissions.last()?.usedBytes)
        job2.cancel()

        // Advance past 60 seconds: observing again triggers a new background refresh
        advanceTimeBy(61_000L)
        val thirdEmissions = mutableListOf<StorageSummary?>()
        val job3 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeStorage("acc1").toList(thirdEmissions)
        }
        advanceUntilIdle()
        assertEquals(2, apiCalls)
        assertEquals(1502L, thirdEmissions.last()?.usedBytes)
        job3.cancel()
    }

    @Test
    fun partialPaginationPersistsContinuationTokenAndDoesNotDeleteCachedRowsUntilComplete() = runTest {
        // Seed existing cached file that will be absent from remote snapshot
        val staleLocal = CloudFile(id = "stale_1", name = "StaleFile.txt", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(staleLocal, "acc1", "root")))

        val seenCursors = mutableListOf<String?>()
        fakeApi.getFilesHandler = { _, _, _, cursor, _, _ ->
            seenCursors += cursor
            if (cursor == null) {
                Response.success(
                    listOf(CloudFile(id = "page_1_file", name = "Page1.txt", type = "file")),
                    Headers.headersOf("X-Next-Cursor", "cursor_page_2")
                )
            } else {
                Response.success(
                    listOf(CloudFile(id = "page_2_file", name = "Page2.txt", type = "file")),
                    Headers.headersOf("ETag", "\"etag-complete\"")
                )
            }
        }

        // First run with maxPages = 1: stops after page 1, persists continuationToken = "cursor_page_2",
        // and MUST NOT delete stale_1 because isCompleteSnapshot == false
        val run1 = repository.refresh("acc1", "root", force = true, maxPages = 1)
        assertTrue(run1.isSuccess)
        val metaAfterRun1 = db.folderSyncMetaDao().getMeta("acc1", "root")!!
        assertEquals("cursor_page_2", metaAfterRun1.continuationToken)
        assertFalse(metaAfterRun1.isCompleteSnapshot)
        assertEquals("PARTIAL", metaAfterRun1.syncState)
        val idsAfterRun1 = db.cloudFileDao().getFilesForFolder("acc1", "root").map { it.id }.toSet()
        assertTrue("Partial sync must never delete cached rows", idsAfterRun1.contains("stale_1"))
        assertTrue(idsAfterRun1.contains("page_1_file"))

        // Second run with maxPages = 1: resumes from persisted "cursor_page_2", reaches end of snapshot
        val run2 = repository.refresh("acc1", "root", force = true, maxPages = 1)
        assertTrue(run2.isSuccess)
        assertEquals(listOf(null, "cursor_page_2"), seenCursors)
        val metaAfterRun2 = db.folderSyncMetaDao().getMeta("acc1", "root")!!
        assertEquals(null, metaAfterRun2.continuationToken)
        assertTrue(metaAfterRun2.isCompleteSnapshot)
        assertEquals("SUCCESS", metaAfterRun2.syncState)

        // Full snapshot across page 1 + page 2 in a single complete run reconciles stale_1 and writes tombstones
        val run3 = repository.refresh("acc1", "root", force = true)
        assertTrue(run3.isSuccess)
        val finalIds = db.cloudFileDao().getFilesForFolder("acc1", "root").map { it.id }.toSet()
        assertEquals(setOf("page_1_file", "page_2_file"), finalIds)
        val tombstones = db.fileTombstoneDao().forFolder("acc1", "root")
        assertTrue(tombstones.any { it.remoteId == "stale_1" })
    }

    @Test
    fun folderSyncWorkerUniqueNameIncludesProviderType() {
        val gdriveWork = com.cloudyte.app.core.sync.FolderSyncWorker.uniqueWorkName("acc1", "Google Drive", "root")
        val tgWork = com.cloudyte.app.core.sync.FolderSyncWorker.uniqueWorkName("acc1", "telegram", "root")
        assertEquals("cloudyte_folder_sync_acc1:google_drive:root", gdriveWork)
        assertEquals("cloudyte_folder_sync_acc1:telegram:root", tgWork)
        assertFalse(gdriveWork == tgWork)
    }

    @Test
    fun refreshStorage_aggregatesPerAccountWhenMultiAccountPrimarySummaryHasNoCategories() = runTest {
        db.accountDao().insertAccounts(
            listOf(
                com.cloudyte.app.db.LocalAccount(
                    id = "acc-tg",
                    name = "TG",
                    driveName = "TG",
                    email = null,
                    accountType = "tg_drive",
                    usageBytes = 300L,
                    totalBytes = 0L,
                    percentage = 0f,
                    fileCount = 3,
                    isDefault = true,
                    channelId = "123"
                ),
                com.cloudyte.app.db.LocalAccount(
                    id = "acc-gd",
                    name = "GDrive",
                    driveName = "GDrive",
                    email = "u@example.com",
                    accountType = "gdrive",
                    usageBytes = 700L,
                    totalBytes = 10_000L,
                    percentage = 7f,
                    fileCount = 4,
                    isDefault = false,
                    channelId = null
                )
            )
        )

        fakeApi.getStorageSummaryHandler = { accountId ->
            when (accountId) {
                null -> StorageSummary(accountId = "all", accountType = "all", usedBytes = 0L, totalBytes = null, fileCount = null, categories = null)
                "acc-tg" -> StorageSummary(accountId = "acc-tg", accountType = "tg_drive", usedBytes = 300L, totalBytes = 0L, fileCount = 3, categories = StorageCategories(videos = 300L))
                "acc-gd" -> StorageSummary(accountId = "acc-gd", accountType = "gdrive", usedBytes = 700L, totalBytes = 10_000L, fileCount = 4, categories = StorageCategories(images = 500L, documents = 200L))
                else -> error("unexpected $accountId")
            }
        }

        val result = repository.refreshStorage(null, force = true)
        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1000L, summary.usedBytes)
        assertEquals(7, summary.fileCount)
        assertEquals(500L, summary.categories?.images)
        assertEquals(300L, summary.categories?.videos)
        assertEquals(200L, summary.categories?.documents)
    }
}

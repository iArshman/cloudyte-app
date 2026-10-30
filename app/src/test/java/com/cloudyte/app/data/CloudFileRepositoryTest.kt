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
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.api.UserSettingsRequest
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalCloudFile
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

    var renameHandler: suspend (
        fileId: String,
        accountId: String?,
        request: RenameRequest
    ) -> Response<Unit> = { _, _, _ ->
        Response.success(Unit)
    }

    var deleteHandler: suspend (
        fileId: String,
        accountId: String?
    ) -> Response<Unit> = { _, _ ->
        Response.success(Unit)
    }

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

    override suspend fun createFolder(accountId: String?, request: FolderRequest): Response<Unit> =
        createFolderHandler(accountId, request)

    override suspend fun uploadFile(
        accountId: String,
        folderIdQuery: String?,
        file: MultipartBody.Part,
        folderIdPart: RequestBody?,
        parentIdPart: RequestBody?
    ): Response<ResponseBody> = Response.success("{}".toResponseBody("application/json".toMediaType()))

    override suspend fun downloadFile(accountId: String, fileId: String, preview: Int?): ResponseBody =
        "".toResponseBody("application/octet-stream".toMediaType())

    override suspend fun renameFile(
        fileId: String,
        accountId: String?,
        request: RenameRequest
    ): Response<Unit> {
        return renameHandler(fileId, accountId, request)
    }

    override suspend fun deleteFile(fileId: String, accountId: String?): Response<Unit> =
        deleteHandler(fileId, accountId)

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
    fun optimisticRename_succeedsAndKeepsNewNameInRoom() = runTest {
        val original = CloudFile(id = "file_1", name = "OriginalReport.pdf", type = "pdf")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        var nameObservedDuringApiCall: String? = null
        fakeApi.renameHandler = { fileId, _, _ ->
            // Verify Room was ALREADY updated optimistically before the network call finishes
            nameObservedDuringApiCall = db.cloudFileDao().getFileById(fileId)?.name
            Response.success(Unit)
        }

        val result = repository.rename("file_1", "acc1", "RenamedReport.pdf")

        assertTrue(result.isSuccess)
        assertEquals("RenamedReport.pdf", nameObservedDuringApiCall)
        assertEquals("RenamedReport.pdf", db.cloudFileDao().getFileById("file_1")?.name)
    }

    @Test
    fun optimisticRename_rollsBackToOriginalNameOnFailure() = runTest {
        val original = CloudFile(id = "file_2", name = "Budget2026.xlsx", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        var optimisticNameDuringCall: String? = null
        fakeApi.renameHandler = { fileId, _, _ ->
            optimisticNameDuringCall = db.cloudFileDao().getFileById(fileId)?.name
            Response.error(
                500,
                """{"error":"Server rename failure"}""".toResponseBody("application/json".toMediaType())
            )
        }

        val result = repository.rename("file_2", "acc1", "CorruptedName.xlsx")

        assertTrue(result.isFailure)
        // Optimistic update happened first...
        assertEquals("CorruptedName.xlsx", optimisticNameDuringCall)
        // ...and was rolled back to the original name when the API failed
        assertEquals("Budget2026.xlsx", db.cloudFileDao().getFileById("file_2")?.name)
    }

    @Test
    fun optimisticDelete_removesImmediatelyAndRollsBackOnFailure() = runTest {
        val original = CloudFile(id = "del_1", name = "Important.docx", type = "file")
        db.cloudFileDao().insertFiles(listOf(LocalCloudFile.fromCloudFile(original, "acc1", "root")))

        var existsDuringApiCall = true
        fakeApi.deleteHandler = { fileId, _ ->
            existsDuringApiCall = db.cloudFileDao().getFileById(fileId) != null
            Response.error(500, """{"error":"Delete failed"}""".toResponseBody("application/json".toMediaType()))
        }

        val result = repository.delete("del_1", "acc1")
        assertTrue(result.isFailure)
        assertFalse("File should be optimistically removed during API call", existsDuringApiCall)
        assertEquals("Important.docx", db.cloudFileDao().getFileById("del_1")?.name)
    }

    @Test
    fun createFolder_insertsOptimisticallyAndReplacesWithCanonicalFolderOnSuccess() = runTest {
        var folderCountDuringCreate = 0
        fakeApi.createFolderHandler = { _, _ ->
            folderCountDuringCreate = db.cloudFileDao().getFilesForFolder("acc1", "root").size
            Response.success(Unit)
        }
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            Response.success(
                listOf(CloudFile(id = "server_folder_1", name = "Projects", type = "folder", isFolder = true))
            )
        }

        val result = repository.createFolder("Projects", "acc1", "root")
        assertTrue(result.isSuccess)
        assertEquals("Temp folder must be present in Room during createFolder API call", 1, folderCountDuringCreate)

        val finalFolders = db.cloudFileDao().getFilesForFolder("acc1", "root")
        assertEquals(1, finalFolders.size)
        assertEquals("server_folder_1", finalFolders.first().id)
        assertEquals("Projects", finalFolders.first().name)
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
}

package com.example.ui.screens

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.api.Account
import com.example.api.CloudFile
import com.example.data.CloudFileRepository
import com.example.data.FakeCloudyteApi
import com.example.db.AppDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var db: AppDatabase
    private lateinit var fakeApi: FakeCloudyteApi
    private lateinit var repo: CloudFileRepository
    private var currentClockMs: Long = 500_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        fakeApi = FakeCloudyteApi().apply {
            accountsList = listOf(
                Account(id = "acc1", name = "Primary Drive", type = "gdrive", isDefault = true),
                Account(id = "acc2", name = "Secondary Drive", type = "telegram", isDefault = false)
            )
        }
        currentClockMs = 500_000L
        repo = CloudFileRepository(db, fakeApi, clock = { currentClockMs })
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun enterFolder_cancelsPreviousRefreshJobSoSlowFolderDoesNotReplaceNewFolder() = runTest(testDispatcher) {
        val folderADeferred = CompletableDeferred<Response<List<CloudFile>>>()

        fakeApi.getFilesHandler = { _, folderId, _, _, _, _ ->
            when (folderId) {
                "folderA" -> folderADeferred.await()
                "folderB" -> Response.success(
                    listOf(CloudFile(id = "file_b", name = "FolderBDoc.txt", type = "file"))
                )
                else -> Response.success(
                    listOf(CloudFile(id = "file_root", name = "RootDoc.txt", type = "file"))
                )
            }
        }

        val viewModel = FilesViewModel(
            application = application,
            db = db,
            api = fakeApi,
            repo = repo
        )

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.files.collect()
        }

        // Complete initial root load
        advanceUntilIdle()

        // Navigate into folderA (whose network call suspends on folderADeferred)
        viewModel.enterFolder("folderA", "Folder A")
        testDispatcher.scheduler.runCurrent()

        val folderAJob = viewModel.refreshJob
        assertNotNull(folderAJob)
        assertTrue("Folder A refresh job should be active while waiting for response", folderAJob!!.isActive)

        // Before folderA finishes, user switches to folderB
        viewModel.enterFolder("folderB", "Folder B")

        // The previous refreshJob for folderA must be cancelled immediately
        assertTrue("Previous refresh job must be cancelled when folder changes", folderAJob.isCancelled)
        assertEquals("folderB", viewModel.currentFolderId.value)

        // Even if folderA's network response completes afterwards, folderB remains active with folderB's files
        folderADeferred.complete(
            Response.success(listOf(CloudFile(id = "file_a", name = "LateFolderADoc.txt", type = "file")))
        )
        advanceUntilIdle()

        assertEquals("folderB", viewModel.currentFolderId.value)
        val folderBInDb = db.cloudFileDao().getFilesForFolder("acc1", "folderB")
        assertEquals(1, folderBInDb.size)
        assertEquals("file_b", folderBInDb.first().id)
        // And folderA was never written to DB because its refreshJob was cancelled before completion
        assertTrue(db.cloudFileDao().getFilesForFolder("acc1", "folderA").isEmpty())

        collectJob.cancel()
    }

    @Test
    fun folderNavigationAndBackToFreshParent_skipsRedundantParentNetworkCall() = runTest(testDispatcher) {
        fakeApi.getFilesHandler = { _, folderId, _, _, _, _ ->
            when (folderId) {
                "sub1" -> Response.success(
                    listOf(CloudFile(id = "sub_file", name = "SubFile.txt", type = "file")),
                    Headers.headersOf("ETag", "\"etag-sub1\"")
                )
                else -> Response.success(
                    listOf(CloudFile(id = "sub1", name = "SubFolder", type = "folder", isFolder = true)),
                    Headers.headersOf("ETag", "\"etag-root\"")
                )
            }
        }

        val viewModel = FilesViewModel(application, db, fakeApi, repo)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.files.collect()
        }
        advanceUntilIdle()
        assertEquals(1, fakeApi.getFilesCallCount) // Root fetched once

        // Enter subfolder -> fetches sub1 once
        viewModel.enterFolder("sub1", "SubFolder")
        advanceUntilIdle()
        assertEquals(2, fakeApi.getFilesCallCount)
        assertEquals("SubFile.txt", viewModel.files.value.first().name)

        // Navigate back to root while root is still fresh -> NO additional network call!
        assertTrue(viewModel.navigateBack())
        advanceUntilIdle()
        assertEquals("Navigating back to fresh parent folder must not re-fetch from network", 2, fakeApi.getFilesCallCount)
        assertEquals("SubFolder", viewModel.files.value.first().name)

        // Re-entering sub1 while sub1 is still fresh -> NO additional network call!
        viewModel.enterFolder("sub1", "SubFolder")
        advanceUntilIdle()
        assertEquals("Re-entering fresh subfolder must not re-fetch from network", 2, fakeApi.getFilesCallCount)

        collectJob.cancel()
    }

    @Test
    fun accountSwitching_isolatesDataAndSkipsNetworkWhenTargetAccountIsFresh() = runTest(testDispatcher) {
        fakeApi.getFilesHandler = { accountId, _, _, _, _, _ ->
            when (accountId) {
                "acc2" -> Response.success(
                    listOf(CloudFile(id = "acc2_file", name = "TelegramVideo.mp4", type = "video")),
                    Headers.headersOf("ETag", "\"etag-acc2\"")
                )
                else -> Response.success(
                    listOf(CloudFile(id = "acc1_file", name = "DriveSheet.xlsx", type = "file")),
                    Headers.headersOf("ETag", "\"etag-acc1\"")
                )
            }
        }

        val viewModel = FilesViewModel(application, db, fakeApi, repo)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.files.collect()
        }
        advanceUntilIdle()

        assertEquals("acc1", viewModel.currentAccountId.value)
        assertEquals(listOf("DriveSheet.xlsx"), viewModel.files.value.map { it.name })
        assertEquals(1, fakeApi.getFilesCallCount)

        // Switch to acc2 (cold) -> 1 network request for acc2
        viewModel.selectAccount("acc2")
        advanceUntilIdle()
        assertEquals("acc2", viewModel.currentAccountId.value)
        assertEquals(listOf("TelegramVideo.mp4"), viewModel.files.value.map { it.name })
        assertEquals(2, fakeApi.getFilesCallCount)

        // Switch back to acc1 (warm & fresh) -> 0 network requests!
        viewModel.selectAccount("acc1")
        advanceUntilIdle()
        assertEquals("acc1", viewModel.currentAccountId.value)
        assertEquals(listOf("DriveSheet.xlsx"), viewModel.files.value.map { it.name })
        assertEquals("Switching back to a warm account within TTL must skip network fetch", 2, fakeApi.getFilesCallCount)

        collectJob.cancel()
    }

    @Test
    fun networkFailure_setsIsOfflineTrueWhileKeepingCachedFilesVisible() = runTest(testDispatcher) {
        var shouldFail = false
        fakeApi.getFilesHandler = { _, _, _, _, _, _ ->
            if (shouldFail) {
                Response.error(503, """{"error":"Offline"}""".toResponseBody("application/json".toMediaType()))
            } else {
                Response.success(
                    listOf(CloudFile(id = "cached_1", name = "OfflineReady.pdf", type = "pdf"))
                )
            }
        }

        val viewModel = FilesViewModel(application, db, fakeApi, repo)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.files.collect()
        }
        advanceUntilIdle()

        assertFalse(viewModel.isOffline.value)
        assertEquals(1, viewModel.files.value.size)

        // Simulate network outage on forced pull-to-refresh
        shouldFail = true
        viewModel.refresh(force = true)
        advanceUntilIdle()

        assertTrue("isOffline must be true when refresh fails", viewModel.isOffline.value)
        assertEquals("Cached files must remain visible when offline refresh fails", 1, viewModel.files.value.size)
        assertEquals("OfflineReady.pdf", viewModel.files.value.first().name)

        collectJob.cancel()
    }
}

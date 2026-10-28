package com.example.ui.screens

import android.app.Application
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
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        fakeApi = FakeCloudyteApi().apply {
            accountsList = listOf(Account(id = "acc1", name = "Test Drive", type = "gdrive", isDefault = true))
        }
        repo = CloudFileRepository(db, fakeApi)
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
}

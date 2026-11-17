package com.cloudyte.app.ui.screens

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.StorageCategories
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.FakeCloudyteApi
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalStorageSummary
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var db: AppDatabase
    private lateinit var baseApi: FakeCloudyteApi
    private lateinit var repo: CloudFileRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        kotlinx.coroutines.runBlocking {
            UserPreferences.resetAllSettings(application)
        }
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        baseApi = FakeCloudyteApi()
        repo = CloudFileRepository(db, baseApi)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun coldFirstLogin_keepsHasInitialContentFalseUntilParallelFetchesComplete() = runTest(testDispatcher) {
        val profileGate = CompletableDeferred<UserProfile>()
        val accountsGate = CompletableDeferred<List<Account>>()
        val storageGate = CompletableDeferred<StorageSummary>()
        val filesGate = CompletableDeferred<Response<List<CloudFile>>>()

        var profileStarted = false
        var accountsStarted = false
        val storageStartedSignal = CompletableDeferred<Unit>()
        val filesStartedSignal = CompletableDeferred<Unit>()
        var storageStarted = false
        var filesStarted = false
        val storageRequestedAccounts = mutableListOf<String?>()

        val api = object : com.cloudyte.app.api.CloudyteApi by baseApi {
            override suspend fun getProfile(): UserProfile {
                profileStarted = true
                return profileGate.await()
            }

            override suspend fun getAccounts(): List<Account> {
                accountsStarted = true
                return accountsGate.await()
            }

            override suspend fun getStorageSummary(accountId: String?): StorageSummary {
                storageStarted = true
                storageRequestedAccounts.add(accountId)
                storageStartedSignal.complete(Unit)
                return storageGate.await()
            }

            override suspend fun getFiles(
                accountId: String?,
                folderId: String?,
                search: String?,
                cursor: String?,
                limit: Int?,
                ifNoneMatch: String?
            ): Response<List<CloudFile>> {
                filesStarted = true
                filesStartedSignal.complete(Unit)
                return filesGate.await()
            }
        }
        val testRepo = CloudFileRepository(db, api)
        val viewModel = HomeViewModel(application, db, api, testRepo)
        val summaryJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.storageSummary.collect()
        }

        // Before loadData finishes on cold start, screen must stay in full blank loading state
        assertFalse(viewModel.hasInitialContent.value)
        assertTrue(viewModel.isLoading.value)

        viewModel.loadData()
        testDispatcher.scheduler.runCurrent()

        // Profile & accounts launch in parallel immediately without wasted "all" storage request
        assertTrue("getProfile should start immediately", profileStarted)
        assertTrue("getAccounts should start immediately in parallel", accountsStarted)
        assertFalse("Should not request 'all' storage before accounts resolve", storageStarted)
        assertFalse(viewModel.hasInitialContent.value)

        // Complete accounts while profile is still in flight -> storage & files launch in parallel
        accountsGate.complete(
            listOf(
                Account(id = "acc_1", name = "Work Drive", type = "gdrive", isDefault = false),
                Account(id = "acc_2", name = "Primary Drive", type = "gdrive", isDefault = true)
            )
        )
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000L) {
                storageStartedSignal.await()
                filesStartedSignal.await()
            }
        }
        testDispatcher.scheduler.runCurrent()

        assertTrue("getStorageSummary should start in parallel right after accounts arrive", storageStarted)
        assertTrue("getFiles should start in parallel right after accounts arrive", filesStarted)
        assertTrue("Should request storage for default drive acc_2", storageRequestedAccounts.contains("acc_2"))
        assertFalse("Screen must remain blank loading until profile & storage arrive", viewModel.hasInitialContent.value)

        // Even after profile completes, wait for storage & files so cards never flash 0 B
        profileGate.complete(UserProfile(id = "u1", username = "arshman"))
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.hasInitialContent.value)

        storageGate.complete(
            StorageSummary(
                accountId = "acc_2",
                accountType = "gdrive",
                usedBytes = 2048L,
                totalBytes = 10_000L,
                fileCount = 12,
                categories = StorageCategories(images = 1024L, documents = 1024L)
            )
        )
        filesGate.complete(Response.success(listOf(CloudFile(id = "f1", name = "hello.txt", type = "txt"))))
        advanceUntilIdle()

        assertTrue(viewModel.hasInitialContent.value)
        assertFalse(viewModel.isLoading.value)
        assertEquals("arshman", viewModel.profile.value?.username)
        assertEquals("acc_2", viewModel.selectedStorageAccountId.value)
        assertNotNull(viewModel.storageSummary.value)
        assertEquals(2048L, viewModel.storageSummary.value?.usedBytes)

        summaryJob.cancel()
    }

    @Test
    fun warmCacheStart_revealsCachedContentImmediatelyWithoutBlankSpinner() = runTest(testDispatcher) {
        db.accountDao().insertAccounts(
            listOf(
                LocalAccount.fromAccount(
                    Account(id = "acc_default", name = "Main", type = "gdrive", isDefault = true, usage = 500L, total = 5000L)
                )
            )
        )
        db.storageSummaryDao().insert(
            LocalStorageSummary.fromStorageSummary(
                summary = StorageSummary(
                    accountId = "acc_default",
                    accountType = "gdrive",
                    usedBytes = 500L,
                    totalBytes = 5000L,
                    fileCount = 3,
                    categories = StorageCategories(images = 500L)
                ),
                fallbackAccountId = "acc_default",
                updatedAt = System.currentTimeMillis()
            )
        )

        val profileGate = CompletableDeferred<UserProfile>()
        val api = object : com.cloudyte.app.api.CloudyteApi by baseApi {
            override suspend fun getProfile(): UserProfile = profileGate.await()
            override suspend fun getAccounts(): List<Account> = listOf(
                Account(id = "acc_default", name = "Main", type = "gdrive", isDefault = true, usage = 500L, total = 5000L)
            )
        }
        val testRepo = CloudFileRepository(db, api)
        val viewModel = HomeViewModel(application, db, api, testRepo)

        viewModel.loadData()
        testDispatcher.scheduler.runCurrent()

        assertTrue("Warm cache should mark hasInitialContent=true before network finishes", viewModel.hasInitialContent.value)

        profileGate.complete(UserProfile(id = "u1", username = "cached_user"))
        advanceUntilIdle()
        assertFalse(viewModel.isLoading.value)
    }
}

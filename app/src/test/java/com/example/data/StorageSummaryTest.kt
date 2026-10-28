package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.api.StorageCategories
import com.example.api.StorageSummary
import com.example.db.AppDatabase
import com.example.db.LocalStorageSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageSummaryTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeCloudyteApi

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        api = FakeCloudyteApi()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `formatStorageSize formats B KB MB GB and TB with one decimal place`() {
        assertEquals("0 B", formatStorageSize(0L))
        assertEquals("512 B", formatStorageSize(512L))
        assertEquals("1.5 KB", formatStorageSize(1536L))
        assertEquals("12.4 MB", formatStorageSize((12.4 * 1024 * 1024).toLong()))
        assertEquals("52.0 GB", formatStorageSize(52L * 1024 * 1024 * 1024))
        assertEquals("2.5 TB", formatStorageSize((2.5 * 1024.0 * 1024.0 * 1024.0 * 1024.0).toLong()))
    }

    @Test
    fun `calculateSegmentProportions handles all-zero categories`() {
        val zeroCategories = StorageCategories(
            images = 0L,
            documents = 0L,
            videos = 0L,
            archives = 0L,
            audio = 0L,
            other = 0L
        )
        val segments = calculateSegmentProportions(zeroCategories, usedBytes = 0L)
        assertEquals(6, segments.size)
        assertTrue(segments.all { it.bytes == 0L && it.proportion == 0f })
    }

    @Test
    fun `calculateSegmentProportions handles single category`() {
        val singleCategory = StorageCategories(
            images = 0L,
            documents = 0L,
            videos = 800L,
            archives = 0L,
            audio = 0L,
            other = 0L
        )
        val segmentsFull = calculateSegmentProportions(singleCategory, usedBytes = 800L)
        val videoSegment = segmentsFull.first { it.category == StorageCategoryType.VIDEOS }
        assertEquals(1.0f, videoSegment.proportion, 0.0001f)
        assertTrue(segmentsFull.filter { it.category != StorageCategoryType.VIDEOS }.all { it.proportion == 0f })

        val segmentsHalf = calculateSegmentProportions(singleCategory, usedBytes = 1600L)
        val videoHalf = segmentsHalf.first { it.category == StorageCategoryType.VIDEOS }
        assertEquals(0.5f, videoHalf.proportion, 0.0001f)
    }

    @Test
    fun `calculateSegmentProportions computes proportional widths across multiple categories`() {
        val categories = StorageCategories(
            images = 200L,
            documents = 100L,
            videos = 400L,
            archives = 100L,
            audio = 100L,
            other = 100L
        )
        val segments = calculateSegmentProportions(categories, usedBytes = 1000L)
        val byType = segments.associateBy { it.category }

        assertEquals(0.2f, byType.getValue(StorageCategoryType.IMAGES).proportion, 0.0001f)
        assertEquals(0.1f, byType.getValue(StorageCategoryType.DOCUMENTS).proportion, 0.0001f)
        assertEquals(0.4f, byType.getValue(StorageCategoryType.VIDEOS).proportion, 0.0001f)
        assertEquals(0.1f, byType.getValue(StorageCategoryType.ARCHIVES).proportion, 0.0001f)
        assertEquals(0.1f, byType.getValue(StorageCategoryType.AUDIO).proportion, 0.0001f)
        assertEquals(0.1f, byType.getValue(StorageCategoryType.OTHER).proportion, 0.0001f)
    }

    @Test
    fun `formatStorageUsageText and aggregateStorageSummaries handle unlimited and finite drives`() {
        val finiteSummary = StorageSummary(
            accountId = "gdrive_1",
            accountType = "Google Drive",
            usedBytes = 52L * 1024 * 1024 * 1024,
            totalBytes = 64L * 1024 * 1024 * 1024,
            categories = null
        )
        assertEquals("52.0 GB of 64.0 GB used", formatStorageUsageText(finiteSummary))

        val unlimitedSummary = StorageSummary(
            accountId = "tg_1",
            accountType = "Telegram",
            usedBytes = (12.4 * 1024 * 1024 * 1024).toLong(),
            totalBytes = null,
            categories = StorageCategories(images = 2L * 1024 * 1024 * 1024, videos = 10L * 1024 * 1024 * 1024)
        )
        assertTrue(unlimitedSummary.isUnlimited)
        assertEquals("12.4 GB used · Unlimited", formatStorageUsageText(unlimitedSummary))

        // Aggregating a finite drive + an unlimited drive yields Unlimited total and sums usedBytes + categories
        val combined = aggregateStorageSummaries(listOf(finiteSummary, unlimitedSummary))
        assertTrue(combined.isUnlimited)
        assertNull(combined.totalBytes)
        assertEquals(finiteSummary.usedBytes + unlimitedSummary.usedBytes, combined.usedBytes)
        assertEquals(2L * 1024 * 1024 * 1024, combined.categories?.images)
        assertEquals(10L * 1024 * 1024 * 1024, combined.categories?.videos)
        assertEquals("64.4 GB used · Unlimited", formatStorageUsageText(combined))
    }

    @Test
    fun `observeStorage emits saved Room value immediately and throttles background refresh to 60 seconds`() = runTest {
        var nowMs = 100_000L
        val repo = CloudFileRepository(db = db, api = api, clock = { nowMs })

        // Pre-populate Room with a saved StorageSummary
        val initialSaved = StorageSummary(
            accountId = "acc_1",
            accountType = "Telegram",
            usedBytes = 1024L * 1024L * 10L, // 10 MB
            totalBytes = null,
            fileCount = 5,
            categories = StorageCategories(images = 1024L * 1024L * 10L)
        )
        db.storageSummaryDao().upsert(LocalStorageSummary.fromStorageSummary(initialSaved, updatedAt = 50_000L))

        var apiCalls = 0
        api.storageSummaryHandler = { accountId ->
            apiCalls++
            delay(50)
            StorageSummary(
                accountId = accountId ?: "acc_1",
                accountType = "Telegram",
                usedBytes = 1024L * 1024L * 25L, // 25 MB updated
                totalBytes = null,
                fileCount = 12,
                categories = StorageCategories(images = 1024L * 1024L * 25L)
            )
        }

        val emissions = mutableListOf<StorageSummary?>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeStorage("acc_1").take(2).toList(emissions)
        }

        // Immediately emits the cached 10 MB value before network completes
        assertEquals(1, emissions.size)
        assertEquals(1024L * 1024L * 10L, emissions[0]?.usedBytes)

        // Advance time so background refresh completes and updates Room
        testScheduler.advanceUntilIdle()
        job.join()

        assertEquals(2, emissions.size)
        assertEquals(1024L * 1024L * 25L, emissions[1]?.usedBytes)
        assertEquals(1, apiCalls)

        // Collecting again within 60 seconds should NOT trigger another network call
        nowMs += 30_000L
        val cachedAgain = repo.observeStorage("acc_1").first()
        testScheduler.advanceUntilIdle()
        assertEquals(1024L * 1024L * 25L, cachedAgain?.usedBytes)
        assertEquals(1, apiCalls)

        // After 60 seconds, collecting again triggers a fresh background call
        nowMs += 31_000L
        val refreshedEmissions = mutableListOf<StorageSummary?>()
        api.storageSummaryHandler = { accountId ->
            apiCalls++
            delay(50)
            StorageSummary(
                accountId = accountId ?: "acc_1",
                accountType = "Telegram",
                usedBytes = 1024L * 1024L * 40L,
                totalBytes = null
            )
        }
        val job2 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeStorage("acc_1").take(2).toList(refreshedEmissions)
        }
        assertEquals(1, refreshedEmissions.size)
        assertEquals(1024L * 1024L * 25L, refreshedEmissions[0]?.usedBytes)

        testScheduler.advanceUntilIdle()
        job2.join()
        assertEquals(2, apiCalls)
        assertEquals(1024L * 1024L * 40L, refreshedEmissions.last()?.usedBytes)
    }
}

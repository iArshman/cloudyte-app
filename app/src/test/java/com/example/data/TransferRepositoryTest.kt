package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import com.example.db.TransferStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class TransferRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TransferRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        repo = TransferRepository(db.transferDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testEnqueueAndObserveTransfers() = runBlocking {
        val id = repo.enqueueTransfer(
            accountId = "acc_123",
            fileName = "test.png",
            totalBytes = 1024L
        )

        val transfers = repo.observeAll().first()
        assertEquals(1, transfers.size)
        val transfer = transfers[0]
        assertEquals(id, transfer.id)
        assertEquals("test.png", transfer.fileName)
        assertEquals(TransferStatus.PENDING.name, transfer.status)
    }

    @Test
    fun testTransferStateTransitions() = runBlocking {
        val id = repo.enqueueTransfer(
            accountId = "acc_123",
            fileName = "data.doc",
            totalBytes = 2048L
        )

        repo.updateProgress(id, 1024L, 2048L)
        var transfers = repo.observeAll().first()
        assertEquals(TransferStatus.RUNNING.name, transfers[0].status)
        assertEquals(1024L, transfers[0].bytesTransferred)

        repo.pauseTransfer(id)
        transfers = repo.observeAll().first()
        assertEquals(TransferStatus.PAUSED.name, transfers[0].status)

        repo.resumeTransfer(id)
        transfers = repo.observeAll().first()
        assertEquals(TransferStatus.PENDING.name, transfers[0].status)

        repo.markCompleted(id)
        transfers = repo.observeAll().first()
        assertEquals(TransferStatus.COMPLETED.name, transfers[0].status)
    }

    @Test
    fun testMarkFailedAndRetry() = runBlocking {
        val id = repo.enqueueTransfer(
            accountId = "acc_123",
            fileName = "video.mp4",
            totalBytes = 5000L
        )

        repo.markFailed(id, "Network timeout")
        var transfers = repo.observeAll().first()
        assertEquals(TransferStatus.FAILED.name, transfers[0].status)
        assertEquals("Network timeout", transfers[0].errorMessage)

        repo.retryTransfer(id)
        transfers = repo.observeAll().first()
        assertEquals(TransferStatus.PENDING.name, transfers[0].status)
        assertEquals(null, transfers[0].errorMessage)
    }
}

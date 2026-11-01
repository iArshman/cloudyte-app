package com.cloudyte.app.core.transfer

import androidx.room.Room
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDirection
import com.cloudyte.app.db.TransferQueueDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransferQueueDrainerTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun oneDrainerExecutesUploadDownloadAndBackupRows() = runBlocking {
        val dao = database.transferDao()
        dao.insert(LocalTransfer(id = "upload", accountId = "a1", fileName = "upload.bin", direction = TransferDirection.UPLOAD.name))
        dao.insert(LocalTransfer(id = "download", accountId = "a1", fileName = "download.bin", direction = TransferDirection.DOWNLOAD.name))
        dao.insert(LocalTransfer(id = "backup", accountId = "a1", fileName = "backup.bin", direction = TransferDirection.BACKUP.name))
        val queueDao: TransferQueueDao = database.transferQueueDao()
        val executed = mutableListOf<String>()
        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
            ): TransferOutcome {
                assertEquals(leaseOwner, transfer.leaseOwner)
                executed += transfer.direction
                onProgress(transfer.totalBytes, transfer.totalBytes, 10L, 0L)
                return TransferOutcome.Completed
            }
        }
        val drainer = RoomTransferQueueDrainer(
            queueDao = queueDao,
            executor = executor,
            ownerId = "test-process",
            now = { 1_000L },
            leaseDurationMs = 30_000L,
            maxTransfersPerDrain = 3
        )

        val result = drainer.drain(DrainTrigger.APP_START, { false }) { }

        assertTrue(result is DrainResult.Drained)
        assertEquals(3, (result as DrainResult.Drained).processed)
        assertEquals(setOf("UPLOAD", "DOWNLOAD", "BACKUP"), executed.toSet())
        assertTrue(dao.getById("upload")?.state == TransferState.COMPLETED.name)
        assertTrue(dao.getById("download")?.state == TransferState.COMPLETED.name)
        assertTrue(dao.getById("backup")?.state == TransferState.COMPLETED.name)
    }
}

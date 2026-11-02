package com.cloudyte.app.core.transfer

import androidx.room.Room
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDirection
import com.cloudyte.app.db.TransferQueueDao
import com.cloudyte.app.db.TransferStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        ActiveTransferSignalRegistry.clear("upload")
        ActiveTransferSignalRegistry.clear("download")
        ActiveTransferSignalRegistry.clear("backup")
        ActiveTransferSignalRegistry.clear("cancel-active")
        ActiveTransferSignalRegistry.clear("pause-active")
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

    @Test
    fun activeCancelDuringProgressAbortsSessionAndTransitionsToCancelled() = runBlocking {
        val dao = database.transferDao()
        dao.insert(
            LocalTransfer(
                id = "cancel-active",
                accountId = "a1",
                fileName = "large.bin",
                direction = TransferDirection.UPLOAD.name,
                totalBytes = 1_000L,
                providerSessionId = "sess-cancel-1"
            )
        )
        val queueDao = database.transferQueueDao()
        val cancelledSessions = mutableListOf<String?>()

        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
            ): TransferOutcome {
                // Simulate user cancelling while chunk 1 is in progress
                dao.updateStatus(
                    id = transfer.id,
                    status = TransferStatus.CANCELLED.name,
                    error = "Cancelled by user"
                )
                ActiveTransferSignalRegistry.signalCancel(transfer.id)
                onProgress(250L, 1_000L, 100L, 7L)
                return TransferOutcome.Completed
            }

            override suspend fun onCancelled(transfer: LocalTransfer) {
                cancelledSessions += transfer.providerSessionId
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = queueDao,
            executor = executor,
            ownerId = "test-process",
            now = { 2_000L },
            leaseDurationMs = 30_000L,
            maxTransfersPerDrain = 1
        )

        drainer.drain(DrainTrigger.USER_REQUEST, { false }) { }

        val updated = dao.getById("cancel-active")!!
        assertEquals(TransferState.CANCELLED.name, updated.state)
        assertEquals(TransferStatus.CANCELLED.name, updated.status)
        assertNull(updated.leaseOwner)
        assertEquals(listOf("sess-cancel-1"), cancelledSessions)
    }

    @Test
    fun activePauseDuringProgressTransitionsDeterministicallyToPaused() = runBlocking {
        val dao = database.transferDao()
        dao.insert(
            LocalTransfer(
                id = "pause-active",
                accountId = "a1",
                fileName = "movie.mp4",
                direction = TransferDirection.DOWNLOAD.name,
                totalBytes = 2_000L
            )
        )
        val queueDao = database.transferQueueDao()

        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
            ): TransferOutcome {
                dao.updateStatus(
                    id = transfer.id,
                    status = TransferStatus.PAUSED.name
                )
                ActiveTransferSignalRegistry.signalPause(transfer.id)
                onProgress(500L, 2_000L, 250L, 6L)
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = queueDao,
            executor = executor,
            ownerId = "test-process",
            now = { 2_000L },
            leaseDurationMs = 30_000L,
            maxTransfersPerDrain = 1
        )

        drainer.drain(DrainTrigger.USER_REQUEST, { false }) { }

        val updated = dao.getById("pause-active")!!
        assertEquals(TransferState.PAUSED.name, updated.state)
        assertEquals(TransferStatus.PAUSED.name, updated.status)
        assertNull(updated.leaseOwner)
    }
}

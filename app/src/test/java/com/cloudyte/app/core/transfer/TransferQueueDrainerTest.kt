package com.cloudyte.app.core.transfer

import androidx.room.Room
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDirection
import com.cloudyte.app.db.TransferQueueDao
import com.cloudyte.app.db.TransferStatus
import kotlinx.coroutines.launch
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

    @Test
    fun workerInterruptionsAndPolicyDeferralsDoNotInflateAttemptCount() = runBlocking {
        val dao = database.transferDao()
        dao.insert(
            LocalTransfer(
                id = "interrupt-resilient",
                accountId = "a1",
                fileName = "video.mp4",
                direction = TransferDirection.UPLOAD.name,
                totalBytes = 4_096L
            )
        )
        val queueDao = database.transferQueueDao()

        var remainingWorkerInterruptions = 3
        var workerStopped = false
        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
            ): TransferOutcome {
                if (remainingWorkerInterruptions > 0) {
                    remainingWorkerInterruptions--
                    workerStopped = true
                    throw kotlinx.coroutines.CancellationException("Simulated worker replacement")
                }
                onProgress(4_096L, 4_096L, 1_024L, 0L)
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = queueDao,
            executor = executor,
            ownerId = "test-process",
            now = { 5_000L },
            leaseDurationMs = 30_000L,
            maxRetryCount = { 2 },
            maxTransfersPerDrain = 1
        )

        // 3 worker cancellations exceed maxRetryCount(2), but because they are worker interruptions
        // (not transfer failures), attemptCount must remain 0 and the transfer must return to QUEUED.
        repeat(3) {
            workerStopped = false
            val job = kotlinx.coroutines.CoroutineScope( kotlinx.coroutines.SupervisorJob() ).launch {
                drainer.drain(DrainTrigger.USER_ENQUEUE, { workerStopped }) { }
            }
            job.join()
            val afterCancel = dao.getById("interrupt-resilient")!!
            assertEquals(TransferState.QUEUED.name, afterCancel.state)
            assertNull(afterCancel.leaseOwner)
            assertEquals(0, afterCancel.attemptCount)
        }

        // Next drain completes the transfer with attemptCount still 0.
        workerStopped = false
        drainer.drain(DrainTrigger.USER_ENQUEUE, { workerStopped }) { }
        val completed = dao.getById("interrupt-resilient")!!
        assertEquals(TransferState.COMPLETED.name, completed.state)
        assertEquals(0, completed.attemptCount)
    }

    @Test
    fun transferSchedulerKickReplacesEnqueuedKeepsRunningAndSchedulesBackupResume(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = androidx.work.WorkManager.getInstance(context)
        val scheduler = TransferScheduler(context)

        // State-aware policy helper deterministically keeps RUNNING and replaces ENQUEUED/stale
        assertEquals(
            androidx.work.ExistingWorkPolicy.KEEP,
            scheduler.resolveWorkPolicyForStates(listOf(androidx.work.WorkInfo.State.RUNNING))
        )
        assertEquals(
            androidx.work.ExistingWorkPolicy.REPLACE,
            scheduler.resolveWorkPolicyForStates(listOf(androidx.work.WorkInfo.State.ENQUEUED))
        )
        assertEquals(
            androidx.work.ExistingWorkPolicy.REPLACE,
            scheduler.resolveWorkPolicyForStates(listOf(androidx.work.WorkInfo.State.BLOCKED))
        )

        scheduler.kick(ScheduleReason.USER_ENQUEUE, allowMetered = true)
        val firstInfos = wm.getWorkInfosForUniqueWork(TransferScheduler.UNIQUE_QUEUE_NAME).get()
        assertEquals(1, firstInfos.size)
        val originalWorkId = firstInfos.single().id

        // While ENQUEUED, re-kicking replaces stale constraints/backoff cleanly
        scheduler.kick(ScheduleReason.NETWORK_RESTORED, allowMetered = true)
        val replacedInfos = wm.getWorkInfosForUniqueWork(TransferScheduler.UNIQUE_QUEUE_NAME).get()
        assertEquals(1, replacedInfos.size)
        val replacedId = replacedInfos.single().id
        assertTrue("ENQUEUED work should be replaced on constraint/network kick", replacedId != originalWorkId)

        // Unmetered background kick uses a separate unique work name
        scheduler.kick(ScheduleReason.USER_ENQUEUE, allowMetered = false)
        val unmeteredInfos = wm.getWorkInfosForUniqueWork(TransferScheduler.UNIQUE_UNMETERED_QUEUE_NAME).get()
        assertEquals(1, unmeteredInfos.size)

        // BackupResumeWorker is scheduled under BACKUP_RESUME_WORK_NAME with constraint requirements
        scheduler.scheduleBackupResume(chargingOnly = true, wifiOnly = true)
        val backupResumeInfos = wm.getWorkInfosForUniqueWork(TransferScheduler.BACKUP_RESUME_WORK_NAME).get()
        assertEquals(1, backupResumeInfos.size)
        assertTrue(backupResumeInfos.single().constraints.requiresCharging())
        assertEquals(androidx.work.NetworkType.UNMETERED, backupResumeInfos.single().constraints.requiredNetworkType)
    }

    @Test
    fun startupCleanupPreservesStagingAndPartFilesForFailedRetryableAndWaitingTransfers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dao = database.transferDao()

        dao.insert(LocalTransfer(id = "t-retryable", accountId = "a1", fileName = "a.bin", status = "FAILED", state = TransferState.FAILED_RETRYABLE.name))
        dao.insert(LocalTransfer(id = "t-wait-net", accountId = "a1", fileName = "b.bin", status = "PENDING", state = TransferState.WAITING_FOR_NETWORK.name))
        dao.insert(LocalTransfer(id = "t-wait-auth", accountId = "a1", fileName = "c.bin", status = "PENDING", state = TransferState.WAITING_FOR_AUTH.name))
        dao.insert(LocalTransfer(id = "t-paused", accountId = "a1", fileName = "d.bin", status = "PAUSED", state = TransferState.PAUSED.name))
        dao.insert(LocalTransfer(id = "t-completed", accountId = "a1", fileName = "e.bin", status = "COMPLETED", state = TransferState.COMPLETED.name))
        dao.insert(LocalTransfer(id = "t-perm-fail", accountId = "a1", fileName = "f.bin", status = "FAILED", state = TransferState.FAILED_PERMANENT.name))

        val resumableIds = dao.getResumableTransferIds().toSet()
        assertTrue(resumableIds.contains("t-retryable"))
        assertTrue(resumableIds.contains("t-wait-net"))
        assertTrue(resumableIds.contains("t-wait-auth"))
        assertTrue(resumableIds.contains("t-paused"))
        assertTrue(!resumableIds.contains("t-completed"))
        assertTrue(!resumableIds.contains("t-perm-fail"))

        val retryableStaging = com.cloudyte.app.core.security.SecureFileCleanup.getStagingFileForTransfer(context, "t-retryable").apply {
            writeText("encrypted-staging-bytes")
        }
        val retryablePart = com.cloudyte.app.core.security.SecureFileCleanup.getEncryptedDownloadPartFile(context, "t-retryable").apply {
            writeText("encrypted-download-part")
        }
        val waitNetStaging = com.cloudyte.app.core.security.SecureFileCleanup.getStagingFileForTransfer(context, "t-wait-net").apply {
            writeText("waiting-network-staging")
        }
        val staleCompletedStaging = com.cloudyte.app.core.security.SecureFileCleanup.getStagingFileForTransfer(context, "t-completed").apply {
            writeText("completed-orphan")
            setLastModified(1_000L)
        }

        try {
            com.cloudyte.app.core.security.SecureFileCleanup.cleanupStaleTempFiles(
                context = context,
                activeTransferIds = resumableIds,
                nowMs = System.currentTimeMillis(),
                purgeAll = false
            )

            assertTrue("FAILED_RETRYABLE upload staging file must survive startup cleanup", retryableStaging.exists())
            assertTrue("FAILED_RETRYABLE download .part file must survive startup cleanup", retryablePart.exists())
            assertTrue("WAITING_FOR_NETWORK staging file must survive startup cleanup", waitNetStaging.exists())
            assertTrue("Stale COMPLETED staging file must be cleaned up", !staleCompletedStaging.exists())
        } finally {
            retryableStaging.delete()
            retryablePart.delete()
            waitNetStaging.delete()
            staleCompletedStaging.delete()
        }
    }

    @Test
    fun rapidPauseAndResumePreservesActiveLeaseUntilWorkerUnwindsAndResumesCleanly() = runBlocking {
        val dao = database.transferDao()
        val queueDao = database.transferQueueDao()
        val repository = com.cloudyte.app.data.TransferRepository(dao)
        val baseNow = System.currentTimeMillis()

        dao.insert(
            LocalTransfer(
                id = "rapid-pause-resume",
                accountId = "a1",
                fileName = "archive.zip",
                direction = TransferDirection.DOWNLOAD.name,
                totalBytes = 4_000L
            )
        )

        var executionCount = 0
        var afterPauseSnapshot: LocalTransfer? = null
        var afterResumeSnapshot: LocalTransfer? = null
        var firstLeaseOwner: String? = null
        var concurrentLeaseDuringUnwind: LocalTransfer? = null

        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
            ): TransferOutcome {
                executionCount++
                if (executionCount == 1) {
                    firstLeaseOwner = leaseOwner
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        // User pauses and then immediately resumes from the UI while Worker 1 is still mid-flight
                        repository.pauseTransfer(transfer.id)
                        afterPauseSnapshot = dao.getById(transfer.id)

                        repository.resumeTransfer(transfer.id)
                        afterResumeSnapshot = dao.getById(transfer.id)

                        // A second concurrent worker must NOT be able to acquire a lease while Worker 1 still holds it
                        concurrentLeaseDuringUnwind = queueDao.acquireNextRunnableLease(
                            "second-worker",
                            baseNow,
                            baseNow + 30_000L
                        )
                    }
                    // Simulate Worker 1 unwinding from the pause signal it received when pauseTransfer was called
                    throw TransferInterruptedException(TransferOutcome.Paused)
                }
                onProgress(4_000L, 4_000L, 1_000L, 0L)
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = queueDao,
            executor = executor,
            ownerId = "test-process",
            now = { baseNow },
            leaseDurationMs = 30_000L,
            maxTransfersPerDrain = 2
        )

        val result = drainer.drain(DrainTrigger.USER_REQUEST, { false }) { }
        assertTrue(result is DrainResult.Drained)
        assertEquals(TransferState.PAUSING.name, afterPauseSnapshot?.state)
        assertEquals(firstLeaseOwner, afterPauseSnapshot?.leaseOwner)
        assertEquals(TransferState.QUEUED.name, afterResumeSnapshot?.state)
        assertEquals("Active lease must remain fenced until Worker 1 unwinds", firstLeaseOwner, afterResumeSnapshot?.leaseOwner)
        assertNull("Second worker must be blocked from acquiring a concurrent lease during unwind", concurrentLeaseDuringUnwind)
        assertEquals("Transfer should unwind pause into QUEUED and complete on the next iteration", 2, executionCount)

        val finalRow = dao.getById("rapid-pause-resume")!!
        assertEquals(TransferState.COMPLETED.name, finalRow.state)
        assertNull(finalRow.leaseOwner)
    }

    @Test
    fun boundedParallelDrainerExecutesMultipleTransfersConcurrently(): Unit = runBlocking {
        val dao = database.transferDao()
        dao.insert(LocalTransfer(id = "par-1", accountId = "a1", fileName = "p1.bin", direction = TransferDirection.UPLOAD.name, totalBytes = 100L))
        dao.insert(LocalTransfer(id = "par-2", accountId = "a1", fileName = "p2.bin", direction = TransferDirection.UPLOAD.name, totalBytes = 100L))
        dao.insert(LocalTransfer(id = "par-3", accountId = "a1", fileName = "p3.bin", direction = TransferDirection.DOWNLOAD.name, totalBytes = 100L))

        val activeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrentObserved = java.util.concurrent.atomic.AtomicInteger(0)
        val claimedLeases = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (Long, Long, Long, Long?) -> Unit
            ): TransferOutcome {
                claimedLeases += leaseOwner
                val nowActive = activeCount.incrementAndGet()
                maxConcurrentObserved.updateAndGet { maxOf(it, nowActive) }
                kotlinx.coroutines.delay(120L)
                onProgress(100L, 100L, 1000L, 0L)
                activeCount.decrementAndGet()
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = database.transferQueueDao(),
            executor = executor,
            ownerId = "parallel-proc",
            maxTransfersPerDrain = 3,
            maxConcurrency = { 3 }
        )

        val result = drainer.drain(DrainTrigger.USER_ENQUEUE, { false }) {}
        assertTrue(result is DrainResult.Drained)
        assertEquals(3, (result as DrainResult.Drained).processed)
        assertTrue("Expected concurrent transfer execution >= 2, saw ${maxConcurrentObserved.get()}", maxConcurrentObserved.get() >= 2)
        assertEquals(3, claimedLeases.size)
    }

    @Test
    fun stallWatchdogAbortsHungTransferAsRetryableAndPreservesSession(): Unit = runBlocking {
        val dao = database.transferDao()
        dao.insert(
            LocalTransfer(
                id = "stalled-1",
                accountId = "a1",
                fileName = "hung.bin",
                direction = TransferDirection.UPLOAD.name,
                providerSessionId = "sess-keep-99",
                totalBytes = 10_000L
            )
        )

        val hungExecutor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (Long, Long, Long, Long?) -> Unit
            ): TransferOutcome {
                kotlinx.coroutines.delay(5_000L)
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = database.transferQueueDao(),
            executor = hungExecutor,
            ownerId = "stall-proc",
            maxTransfersPerDrain = 1,
            maxRetryCount = { 3 },
            stallTimeoutMs = 150L
        )

        val result = drainer.drain(DrainTrigger.USER_ENQUEUE, { false }) {}
        assertTrue(result is DrainResult.Drained)
        val row = dao.getById("stalled-1")!!
        assertEquals(TransferState.FAILED_RETRYABLE.name, row.state)
        assertEquals("STALL_TIMEOUT", row.lastErrorCode)
        assertEquals("sess-keep-99", row.providerSessionId)
        assertNull(row.leaseOwner)
    }

    @Test
    fun backupPolicyHoldParksAutoBackupsAndInvokesResumeWhileDownloadRuns(): Unit = runBlocking {
        val dao = database.transferDao()
        dao.insert(
            LocalTransfer(
                id = "auto-bkp-1",
                accountId = "a1",
                fileName = "cam1.jpg",
                direction = TransferDirection.BACKUP.name,
                operationType = "BACKUP",
                priority = 10,
                totalBytes = 100L
            )
        )
        dao.insert(
            LocalTransfer(
                id = "auto-bkp-2",
                accountId = "a1",
                fileName = "cam2.jpg",
                direction = TransferDirection.BACKUP.name,
                operationType = "BACKUP",
                priority = 10,
                totalBytes = 100L
            )
        )
        dao.insert(
            LocalTransfer(
                id = "user-dl-1",
                accountId = "a1",
                fileName = "manual_doc.pdf",
                direction = TransferDirection.DOWNLOAD.name,
                operationType = "DOWNLOAD",
                priority = 1,
                totalBytes = 100L
            )
        )

        val monitor = object : com.cloudyte.app.core.network.NetworkMonitor {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(com.cloudyte.app.core.network.NetworkStatus.VALIDATED_METERED)
            override fun current() = com.cloudyte.app.core.network.NetworkStatus.VALIDATED_METERED
            override fun snapshot(preferences: com.cloudyte.app.data.UserPreferencesState) =
                com.cloudyte.app.core.network.NetworkPolicySnapshot(
                    status = com.cloudyte.app.core.network.NetworkStatus.VALIDATED_METERED,
                    allowMeteredTransfers = true,
                    allowRoaming = false,
                    requireChargingForBackup = true,
                    batterySaverActive = false,
                    isCharging = false,
                    batteryNotLow = true,
                    requireWifiForBackup = true
                )
        }
        val gate = com.cloudyte.app.core.network.TransferNetworkGate(
            monitor = monitor,
            policy = com.cloudyte.app.core.network.DefaultNetworkPolicy(),
            preferences = { com.cloudyte.app.data.UserPreferencesState(allowMeteredTransfers = true, backupWifiOnly = true, backupChargingOnly = true) }
        )

        var holdCallbackCharging = false
        var holdCallbackWifi = false
        var holdCallbacks = 0

        val executor = object : TransferOperationExecutor {
            override suspend fun execute(
                transfer: LocalTransfer,
                leaseOwner: String,
                onProgress: suspend (Long, Long, Long, Long?) -> Unit
            ): TransferOutcome {
                onProgress(100L, 100L, 100L, 0L)
                return TransferOutcome.Completed
            }
        }

        val drainer = RoomTransferQueueDrainer(
            queueDao = database.transferQueueDao(),
            executor = executor,
            ownerId = "hold-proc",
            maxTransfersPerDrain = 6,
            networkGate = gate,
            maxConcurrency = { 2 },
            onBackupPolicyHold = { reqCharging, reqWifi ->
                holdCallbacks++
                holdCallbackCharging = reqCharging
                holdCallbackWifi = reqWifi
            }
        )

        val result = drainer.drain(DrainTrigger.USER_ENQUEUE, { false }) {}
        assertTrue(result is DrainResult.Drained)
        assertEquals(1, holdCallbacks)
        assertTrue(holdCallbackCharging || holdCallbackWifi)
        assertEquals(TransferState.WAITING_FOR_NETWORK.name, dao.getById("auto-bkp-1")?.state)
        assertEquals(TransferState.WAITING_FOR_NETWORK.name, dao.getById("auto-bkp-2")?.state)
        assertEquals(TransferState.COMPLETED.name, dao.getById("user-dl-1")?.state)
    }
}

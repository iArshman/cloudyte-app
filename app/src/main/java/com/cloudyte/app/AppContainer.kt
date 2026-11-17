package com.cloudyte.app

import android.content.Context
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.core.common.DefaultDispatcherProvider
import com.cloudyte.app.core.common.DispatcherProvider
import com.cloudyte.app.core.transfer.FileChunkStore
import com.cloudyte.app.core.transfer.ChunkStore
import com.cloudyte.app.core.transfer.RetryPolicy
import com.cloudyte.app.core.transfer.TransferCoordinator
import com.cloudyte.app.core.transfer.CloudTransferOperationExecutor
import com.cloudyte.app.core.transfer.RoomTransferQueueDrainer
import com.cloudyte.app.core.transfer.TransferScheduler
import com.cloudyte.app.core.transfer.ScheduleReason
import com.cloudyte.app.core.network.ConnectivityNetworkMonitor
import com.cloudyte.app.core.network.DefaultNetworkPolicy
import com.cloudyte.app.core.network.TransferNetworkGate
import com.cloudyte.app.core.cache.ThumbnailStore
import com.cloudyte.app.core.cache.PreviewStore
import com.cloudyte.app.core.cache.CacheQuotaManager
import com.cloudyte.app.core.transfer.TransferDiagnostics
import com.cloudyte.app.core.release.TransferRolloutGate
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.data.repository.FileRepositoryImpl
import com.cloudyte.app.data.repository.AccountRepositoryImpl
import com.cloudyte.app.data.repository.TransferRepositoryImpl
import com.cloudyte.app.data.providers.ProviderRegistry
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.domain.repository.FileRepository as DomainFileRepository
import com.cloudyte.app.domain.repository.AccountRepository as DomainAccountRepository
import com.cloudyte.app.domain.repository.TransferRepository as DomainTransferRepository
import com.cloudyte.app.domain.usecase.CancelTransfer
import com.cloudyte.app.domain.usecase.CreateFolder
import com.cloudyte.app.domain.usecase.DeleteFile
import com.cloudyte.app.domain.usecase.GetAccounts
import com.cloudyte.app.domain.usecase.ObserveFiles
import com.cloudyte.app.domain.usecase.PauseTransfer
import com.cloudyte.app.domain.usecase.RefreshFolder
import com.cloudyte.app.domain.usecase.RenameFile
import com.cloudyte.app.domain.usecase.ResumeTransfer
import com.cloudyte.app.domain.usecase.RetryTransfer
import com.cloudyte.app.sync.AutoSyncManager
import kotlinx.coroutines.CoroutineScope
import com.cloudyte.app.core.network.isOnline
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.os.Process
import com.cloudyte.app.core.transfer.DrainResult
import com.cloudyte.app.core.transfer.DrainTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Composition root for application-scoped dependencies.
 *
 * Feature code can gradually depend on this container while the existing
 * package structure is migrated toward core/data/domain/features boundaries.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    val networkMonitor: ConnectivityNetworkMonitor = ConnectivityNetworkMonitor(appContext) {
        applicationScope.launch(dispatchers.io) {
            ApiClient.evictIdleConnections()
        }
    }
    val networkPolicy = DefaultNetworkPolicy()
    val transferNetworkGate = TransferNetworkGate(
        monitor = networkMonitor,
        policy = networkPolicy,
        preferences = { com.cloudyte.app.data.UserPreferences.observe(appContext).first() }
    )
    val retryPolicy: RetryPolicy = RetryPolicy()
    val chunkStore: ChunkStore by lazy { FileChunkStore(appContext) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(ApiClient.apiService)
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(appContext)
    }

    val contentKeyManager: com.cloudyte.app.core.security.ContentKeyManager by lazy {
        com.cloudyte.app.core.security.ContentKeyManager(appContext)
    }

    val streamFileCrypto: com.cloudyte.app.core.security.StreamFileCrypto by lazy {
        com.cloudyte.app.core.security.StreamFileCrypto()
    }

    val metadataCodec: com.cloudyte.app.core.security.EncryptedMetadataCodec by lazy {
        com.cloudyte.app.core.security.EncryptedMetadataCodec()
    }

    val keyBackupCodec: com.cloudyte.app.core.security.KeyBackupCodec by lazy {
        com.cloudyte.app.core.security.KeyBackupCodec(contentKeyManager)
    }

    val thumbnailStore: ThumbnailStore by lazy {
        ThumbnailStore(appContext, database.thumbnailDao(), contentKeyManager, streamFileCrypto)
    }

    val previewStore: PreviewStore by lazy { PreviewStore(appContext) }

    val cacheQuotaManager: CacheQuotaManager by lazy {
        CacheQuotaManager(appContext, database.thumbnailDao())
    }

    val cloudFileRepository: CloudFileRepository by lazy {
        CloudFileRepository(
            db = database,
            api = ApiClient.apiService,
            contentKeyProvider = contentKeyManager,
            metadataCodec = metadataCodec
        )
    }

    val transferRepository: TransferRepository by lazy {
        TransferRepository(database.transferDao(), providerRegistry)
    }

    val transferDiagnostics: TransferDiagnostics by lazy { TransferDiagnostics() }

    val transferScheduler: TransferScheduler by lazy {
        TransferScheduler(appContext, transferDiagnostics)
    }

    private val immediateDrainMutex = Mutex()
    private val immediateDrainRequested = AtomicBoolean(false)
    private val _isImmediateDrainActive = MutableStateFlow(false)
    val isImmediateDrainActive: StateFlow<Boolean> = _isImmediateDrainActive.asStateFlow()
    private val _liveTransferSnapshot = MutableStateFlow<com.cloudyte.app.core.transfer.TransferProgressSnapshot?>(null)
    val liveTransferSnapshot: StateFlow<com.cloudyte.app.core.transfer.TransferProgressSnapshot?> = _liveTransferSnapshot.asStateFlow()
    private val _foregroundEpoch = MutableStateFlow(0L)
    val foregroundEpoch: StateFlow<Long> = _foregroundEpoch.asStateFlow()
    private val lastForegroundAtMs = AtomicLong(0L)
    private val startupCleanupCompleted = AtomicBoolean(false)
    private val startupCleanupMutex = Mutex()

    /**
     * Called when the application transitions back to the foreground (`onActivityStarted` from background).
     * Immediately re-validates connectivity, drops stale pooled sockets, notifies active screens
     * via [foregroundEpoch], and resumes any pending transfers/mutations if online.
     */
    fun onAppForegrounded(nowMs: Long = System.currentTimeMillis()) {
        val status = networkMonitor.refresh()
        val prev = lastForegroundAtMs.get()
        if (prev != 0L && (nowMs - prev) < FOREGROUND_RESUME_COOLDOWN_MS) {
            return
        }
        lastForegroundAtMs.set(nowMs)
        _foregroundEpoch.update { it + 1L }

        applicationScope.launch(dispatchers.io) {
            ApiClient.evictIdleConnections()
            if (status.isOnline()) {
                runCatching {
                    if (database.fileMutationDao().pendingDue().isNotEmpty()) {
                        com.cloudyte.app.sync.FileMutationWorker.enqueue()
                    }
                }
                runCatching {
                    val queueDao = database.transferQueueDao()
                    val now = System.currentTimeMillis()
                    queueDao.recoverExpiredLeases(now)
                    queueDao.requeueNetworkWaiters(now)
                    if (queueDao.hasRunnableTransfers(now)) {
                        runCatching { transferScheduler.kick(ScheduleReason.APP_START, allowMetered = true) }
                        kickImmediateDrain(DrainTrigger.APP_START, "app_foreground")
                    }
                }
            }
        }
    }

    suspend fun runStartupCleanupOnce() {
        if (startupCleanupCompleted.get()) return
        startupCleanupMutex.lock()
        try {
            if (!startupCleanupCompleted.get()) {
                runCatching {
                    val now = System.currentTimeMillis()
                    database.transferQueueDao().recoverExpiredLeases(now)
                    val activeIds = database.transferDao().getResumableTransferIds().toSet()
                    com.cloudyte.app.core.security.SecureFileCleanup.cleanupStaleTempFiles(appContext, activeIds, now)
                }
                startupCleanupCompleted.set(true)
            }
        } finally {
            startupCleanupMutex.unlock()
        }
    }

    /**
     * Immediately drains runnable transfers in-process on [dispatchers.io] using the atomic Room
     * lease queue so user uploads/downloads start in 0ms without stalling behind OEM WorkManager
     * throttling, while promoting [TransferForegroundService] (`dataSync`) so the OS does not
     * abort active sockets when leaving the app, and keeping WorkManager as process-death recovery.
     */
    fun kickImmediateDrain(
        trigger: DrainTrigger = DrainTrigger.USER_ENQUEUE,
        reason: String? = null,
        startForegroundService: Boolean = true
    ) {
        immediateDrainRequested.set(true)
        val isInteractiveTrigger = trigger == DrainTrigger.USER_ENQUEUE ||
            trigger == DrainTrigger.USER_RETRY ||
            trigger == DrainTrigger.USER_REQUEST
        if (startForegroundService && isInteractiveTrigger) {
            com.cloudyte.app.core.transfer.TransferForegroundService.startIfAllowed(appContext, trigger)
        }
        applicationScope.launch(dispatchers.io) {
            runStartupCleanupOnce()
            while (immediateDrainRequested.get()) {
                if (!immediateDrainMutex.tryLock()) break
                _isImmediateDrainActive.value = true
                try {
                    while (immediateDrainRequested.getAndSet(false)) {
                        if (!transferRolloutGate.isEnabled()) break
                        val queueDao = database.transferQueueDao()
                        val now = System.currentTimeMillis()
                        queueDao.recoverExpiredLeases(now)
                        queueDao.requeueNetworkWaiters(now)
                        if (!queueDao.hasRunnableTransfers(now)) continue
                        if (startForegroundService) {
                            com.cloudyte.app.core.transfer.TransferForegroundService.startIfAllowed(appContext, trigger)
                        }
                        transferDiagnostics.recordEvent(
                            operationType = "immediate_drain_start",
                            state = trigger.name,
                            networkState = reason ?: networkMonitor.current().name
                        )
                        var processedTotal = 0
                        var iterations = 0
                        while (iterations < 25) {
                            val result = transferQueueDrainer.drain(trigger, { false }) { snapshot ->
                                _liveTransferSnapshot.value = snapshot
                            }
                            iterations++
                            if (result is DrainResult.Drained) {
                                processedTotal += result.processed
                                if (!result.hasMore || result.processed == 0) break
                            } else {
                                break
                            }
                        }
                        transferDiagnostics.recordEvent(
                            operationType = "immediate_drain_finish",
                            state = "DONE_processed_$processedTotal"
                        )
                        val followNow = System.currentTimeMillis()
                        runCatching {
                            queueDao.nextRetryAt(followNow)?.let { transferScheduler.scheduleRetryAt(it) }
                            queueDao.nextLeaseExpiry(followNow)?.let { transferScheduler.scheduleLeaseRecoveryAt(it) }
                            if (queueDao.hasAutoBackupNetworkWaiters()) {
                                val prefs = runCatching { com.cloudyte.app.data.UserPreferences.observe(appContext).first() }.getOrNull()
                                transferScheduler.scheduleBackupResume(
                                    chargingOnly = prefs?.backupChargingOnly == true,
                                    wifiOnly = prefs?.backupWifiOnly ?: true
                                )
                            }
                            if (queueDao.hasNetworkWaiters()) transferScheduler.scheduleNetworkRecovery()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    transferDiagnostics.recordEvent(
                        operationType = "immediate_drain_error",
                        state = trigger.name,
                        errorCode = error::class.simpleName ?: "DRAIN_ERROR",
                        networkState = error.message?.take(120)
                    )
                } finally {
                    _liveTransferSnapshot.value = null
                    _isImmediateDrainActive.value = false
                    immediateDrainMutex.unlock()
                }
            }
        }
    }

    /** Fail-safe kill switch for staged production rollout; queued rows remain durable when disabled. */
    val transferRolloutGate: TransferRolloutGate by lazy { TransferRolloutGate(appContext) }

    val transferQueueDrainer by lazy {
        RoomTransferQueueDrainer(
            queueDao = database.transferQueueDao(),
            executor = CloudTransferOperationExecutor(
                context = appContext,
                database = database,
                transfers = transferRepository,
                providers = providerRegistry,
                contentKeyProvider = contentKeyManager,
                streamFileCrypto = streamFileCrypto,
                metadataCodec = metadataCodec,
                isNetworkConnected = {
                    val status = networkMonitor.current()
                    status != com.cloudyte.app.core.network.NetworkStatus.UNAVAILABLE &&
                        status != com.cloudyte.app.core.network.NetworkStatus.CAPTIVE_PORTAL
                },
                onLocalDeletionFailed = { transfer, message ->
                    com.cloudyte.app.core.transfer.notification.NotificationHelper.postEventIfEnabled(
                        context = appContext,
                        eventType = com.cloudyte.app.core.transfer.notification.NotificationEventType.BACKUP_FAILURE,
                        title = "Local copy not deleted",
                        message = message,
                        notificationId = transfer.id.hashCode()
                    )
                }
            ),
            ownerId = "${Process.myPid()}-${UUID.randomUUID()}",
            maxTransfersPerDrain = 6,
            networkGate = transferNetworkGate,
            diagnostics = transferDiagnostics,
            maxRetryCount = {
                com.cloudyte.app.data.UserPreferences.observe(appContext).first().transferRetryCount
            },
            onTerminalOutcome = { transfer, outcome ->
                when (outcome) {
                    com.cloudyte.app.core.transfer.TransferOutcome.Completed -> {
                        val eventType = if (transfer.direction == com.cloudyte.app.db.TransferDirection.BACKUP.name) {
                            com.cloudyte.app.core.transfer.notification.NotificationEventType.BACKUP_COMPLETED
                        } else {
                            com.cloudyte.app.core.transfer.notification.NotificationEventType.TRANSFER_COMPLETED
                        }
                        com.cloudyte.app.core.transfer.notification.NotificationHelper.postEventIfEnabled(
                            context = appContext,
                            eventType = eventType,
                            title = if (transfer.direction == com.cloudyte.app.db.TransferDirection.DOWNLOAD.name) "Download complete" else "Upload complete",
                            message = transfer.fileName,
                            notificationId = transfer.id.hashCode()
                        )
                    }
                    is com.cloudyte.app.core.transfer.TransferOutcome.Permanent -> {
                        val eventType = if (transfer.direction == com.cloudyte.app.db.TransferDirection.BACKUP.name) {
                            com.cloudyte.app.core.transfer.notification.NotificationEventType.BACKUP_FAILURE
                        } else {
                            com.cloudyte.app.core.transfer.notification.NotificationEventType.TRANSFER_FAILURE
                        }
                        com.cloudyte.app.core.transfer.notification.NotificationHelper.postEventIfEnabled(
                            context = appContext,
                            eventType = eventType,
                            title = "Transfer failed: ${transfer.fileName}",
                            message = outcome.message,
                            notificationId = transfer.id.hashCode()
                        )
                    }
                    is com.cloudyte.app.core.transfer.TransferOutcome.WaitingForAuth -> {
                        com.cloudyte.app.core.transfer.notification.NotificationHelper.postEventIfEnabled(
                            context = appContext,
                            eventType = com.cloudyte.app.core.transfer.notification.NotificationEventType.TRANSFER_FAILURE,
                            title = "Authentication required",
                            message = "${transfer.fileName}: ${outcome.message}",
                            notificationId = transfer.id.hashCode()
                        )
                    }
                    else -> Unit
                }
            },
            maxConcurrency = { RoomTransferQueueDrainer.DEFAULT_MAX_CONCURRENCY },
            stallTimeoutMs = RoomTransferQueueDrainer.DEFAULT_STALL_TIMEOUT_MS,
            onBackupPolicyHold = { requireCharging, requireWifi ->
                val prefs = runCatching {
                    com.cloudyte.app.data.UserPreferences.observe(appContext).first()
                }.getOrNull()
                transferScheduler.scheduleBackupResume(
                    chargingOnly = requireCharging || (prefs?.backupChargingOnly == true),
                    wifiOnly = requireWifi || (prefs?.backupWifiOnly ?: true)
                )
            }
        )
    }

    val fileDomainRepository: DomainFileRepository by lazy {
        FileRepositoryImpl(cloudFileRepository)
    }

    val transferDomainRepository: DomainTransferRepository by lazy {
        TransferRepositoryImpl(transferRepository)
    }

    val transferCoordinator: TransferCoordinator by lazy {
        TransferCoordinator(
            context = appContext,
            repository = transferDomainRepository,
            scheduleBackgroundWork = { _ ->
                transferScheduler.kick(ScheduleReason.USER_ENQUEUE, allowMetered = true)
                kickImmediateDrain(DrainTrigger.USER_ENQUEUE, "coordinator_schedule")
            },
            diagnostics = transferDiagnostics,
            scheduler = transferScheduler
        )
    }

    val accountDomainRepository: DomainAccountRepository by lazy {
        AccountRepositoryImpl(ApiClient.apiService)
    }
    val getAccounts by lazy { GetAccounts(accountDomainRepository) }

    val observeFiles by lazy { ObserveFiles(fileDomainRepository) }
    val refreshFolder by lazy { RefreshFolder(fileDomainRepository) }
    val renameFile by lazy { RenameFile(fileDomainRepository) }
    val deleteFile by lazy { DeleteFile(fileDomainRepository) }
    val createFolder by lazy { CreateFolder(fileDomainRepository) }
    val retryTransfer by lazy { RetryTransfer(transferDomainRepository) }
    val pauseTransfer by lazy { PauseTransfer(transferDomainRepository) }
    val resumeTransfer by lazy { ResumeTransfer(transferDomainRepository) }
    val cancelTransfer by lazy { CancelTransfer(transferDomainRepository) }

    init {
        networkMonitor.start()
        applicationScope.launch(dispatchers.io) {
            runStartupCleanupOnce()
        }
        applicationScope.launch {
            var wasOnline = networkMonitor.current().isOnline()
            networkMonitor.status
                .distinctUntilChanged()
                .drop(1)
                .collect { status ->
                    val nowOnline = status.isOnline()
                    if (nowOnline) {
                        if (!wasOnline) {
                            applicationScope.launch(dispatchers.io) {
                                ApiClient.evictIdleConnections()
                            }
                        }
                        runCatching {
                            if (database.fileMutationDao().pendingDue().isNotEmpty()) {
                                com.cloudyte.app.sync.FileMutationWorker.enqueue()
                            }
                        }
                        runCatching {
                            transferScheduler.kick(ScheduleReason.NETWORK_RESTORED, allowMetered = true)
                        }
                        kickImmediateDrain(DrainTrigger.NETWORK_RESTORED, "network_restored")
                    }
                    wasOnline = nowOnline
                }
        }
    }

    companion object {
        private const val FOREGROUND_RESUME_COOLDOWN_MS = 3_000L
    }
}

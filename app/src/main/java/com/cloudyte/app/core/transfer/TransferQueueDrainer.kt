package com.cloudyte.app.core.transfer

import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferQueueDao
import com.cloudyte.app.core.network.PolicyDecision
import com.cloudyte.app.core.network.TransferNetworkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class TransferInterruptedException(
    val outcome: TransferOutcome,
    message: String = "Transfer interrupted with $outcome"
) : CancellationException(message)

interface TransferOperationExecutor {
    suspend fun execute(transfer: LocalTransfer, leaseOwner: String, onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit): TransferOutcome
    suspend fun onCompleted(transfer: LocalTransfer) = Unit
    suspend fun onCancelled(transfer: LocalTransfer) = Unit
}

sealed interface TransferOutcome {
    data object Completed : TransferOutcome
    data class Retryable(val code: String, val message: String, val retryAt: Long? = null, val httpStatus: Int? = null) : TransferOutcome
    data class Permanent(val code: String, val message: String, val httpStatus: Int? = null) : TransferOutcome
    data class WaitingForNetwork(val code: String, val message: String) : TransferOutcome
    data class WaitingForAuth(val code: String, val message: String) : TransferOutcome
    data object Paused : TransferOutcome
    data object Cancelled : TransferOutcome
}

enum class DrainTrigger { USER_REQUEST, USER_ENQUEUE, USER_RETRY, NETWORK_RESTORED, APP_START, RETRY, PERIODIC_MAINTENANCE }
sealed interface DrainResult {
    data class Drained(val processed: Int, val hasMore: Boolean) : DrainResult
    data object Stopped : DrainResult
}

interface TransferQueueDrainer {
    suspend fun drain(trigger: DrainTrigger, isStopped: () -> Boolean, onProgress: suspend (TransferProgressSnapshot) -> Unit): DrainResult
}

/** Bounded parallel queue consumer. Stale owners are fenced from writing progress or completion. */
class RoomTransferQueueDrainer(
    private val queueDao: TransferQueueDao,
    private val executor: TransferOperationExecutor,
    private val ownerId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val leaseDurationMs: Long = 2 * 60 * 1000L,
    private val maxTransfersPerDrain: Int = 3,
    private val networkGate: TransferNetworkGate? = null,
    private val diagnostics: TransferDiagnostics? = null,
    private val maxRetryCount: suspend () -> Int = { Int.MAX_VALUE },
    private val onTerminalOutcome: (suspend (LocalTransfer, TransferOutcome) -> Unit)? = null,
    private val maxConcurrency: suspend () -> Int = { DEFAULT_MAX_CONCURRENCY },
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val onBackupPolicyHold: (suspend (requireCharging: Boolean, requireWifi: Boolean) -> Unit)? = null
) : TransferQueueDrainer {
    override suspend fun drain(
        trigger: DrainTrigger,
        isStopped: () -> Boolean,
        onProgress: suspend (TransferProgressSnapshot) -> Unit
    ): DrainResult {
        queueDao.recoverExpiredLeases(now())
        val requestedWorkers = runCatching { maxConcurrency() }.getOrDefault(DEFAULT_MAX_CONCURRENCY).coerceIn(1, 6)
        val workerCount = minOf(requestedWorkers, maxTransfersPerDrain.coerceAtLeast(1))
        val claimedSlots = java.util.concurrent.atomic.AtomicInteger(0)
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val holdAutoBackups = java.util.concurrent.atomic.AtomicBoolean(false)
        val heldNeedsCharging = java.util.concurrent.atomic.AtomicBoolean(false)
        val heldNeedsWifi = java.util.concurrent.atomic.AtomicBoolean(false)
        val progressMutex = kotlinx.coroutines.sync.Mutex()

        fun tryReserveSlot(): Boolean {
            while (true) {
                val current = claimedSlots.get()
                if (current >= maxTransfersPerDrain) return false
                if (claimedSlots.compareAndSet(current, current + 1)) return true
            }
        }

        suspend fun emitProgress(snapshot: TransferProgressSnapshot) {
            progressMutex.lock()
            try {
                onProgress(snapshot)
            } finally {
                progressMutex.unlock()
            }
        }

        coroutineScope {
            val workers = List(workerCount) {
                launch {
                    while (!isStopped() && tryReserveSlot()) {
                        val acquiredAt = now()
                        val leaseOwner = "$ownerId:${UUID.randomUUID()}"
                        val transfer = queueDao.acquireNextRunnableLease(
                            ownerId = leaseOwner,
                            now = acquiredAt,
                            leaseUntil = acquiredAt + leaseDurationMs,
                            excludeAutoBackup = holdAutoBackups.get()
                        )
                        if (transfer == null) {
                            claimedSlots.decrementAndGet()
                            break
                        }
                        try {
                            val preExecutionInterrupt = detectInterruption(transfer.id)
                            if (preExecutionInterrupt != null) {
                                applyOutcome(transfer, leaseOwner, preExecutionInterrupt)
                                processedCount.incrementAndGet()
                                continue
                            }
                            when (val decision = networkGate?.check(transfer)) {
                                is PolicyDecision.WaitingForNetwork,
                                is PolicyDecision.WaitingForCharging,
                                is PolicyDecision.WaitingForBattery,
                                is PolicyDecision.WaitingForRoaming -> {
                                    val reason = when (decision) {
                                        is PolicyDecision.WaitingForNetwork -> decision.reason
                                        is PolicyDecision.WaitingForCharging -> decision.reason
                                        is PolicyDecision.WaitingForBattery -> decision.reason
                                        is PolicyDecision.WaitingForRoaming -> decision.reason
                                        else -> "NETWORK_POLICY"
                                    }
                                    transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_NETWORK, "PENDING", "NETWORK_POLICY", reason)
                                    val isAutoBackup = transfer.direction.equals("BACKUP", ignoreCase = true) &&
                                        transfer.operationType.equals("BACKUP", ignoreCase = true)
                                    val isHoldableConstraint = decision is PolicyDecision.WaitingForCharging ||
                                        decision is PolicyDecision.WaitingForBattery ||
                                        (decision is PolicyDecision.WaitingForNetwork &&
                                            (decision.reason == "BACKUP_WIFI_ONLY" || decision.reason == "METERED_NETWORK_DISABLED"))
                                    if (isAutoBackup && isHoldableConstraint) {
                                        holdAutoBackups.set(true)
                                        if (decision is PolicyDecision.WaitingForCharging) {
                                            heldNeedsCharging.set(true)
                                        }
                                        if (decision is PolicyDecision.WaitingForNetwork &&
                                            (decision.reason == "BACKUP_WIFI_ONLY" || decision.reason == "METERED_NETWORK_DISABLED")
                                        ) {
                                            heldNeedsWifi.set(true)
                                        }
                                        queueDao.parkQueuedAutoBackups(now(), reason)
                                    }
                                    diagnostics?.recordEvent(
                                        operationType = "queue_policy",
                                        transferId = transfer.id,
                                        accountId = transfer.accountId,
                                        providerType = transfer.providerType,
                                        state = TransferState.WAITING_FOR_NETWORK.name,
                                        retryCount = transfer.attemptCount,
                                        errorCode = "NETWORK_POLICY",
                                        networkState = reason,
                                        workerRunId = leaseOwner
                                    )
                                    processedCount.incrementAndGet()
                                    continue
                                }
                                is PolicyDecision.WaitingForAuth -> {
                                    transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_AUTH, "PENDING", "AUTH_REQUIRED", decision.reason)
                                    diagnostics?.recordEvent(
                                        operationType = "queue_policy",
                                        transferId = transfer.id,
                                        accountId = transfer.accountId,
                                        providerType = transfer.providerType,
                                        state = TransferState.WAITING_FOR_AUTH.name,
                                        retryCount = transfer.attemptCount,
                                        errorCode = "AUTH_REQUIRED",
                                        workerRunId = leaseOwner
                                    )
                                    processedCount.incrementAndGet()
                                    continue
                                }
                                PolicyDecision.Allowed, null -> Unit
                            }
                            val outcome = coroutineScope {
                                val executionJob = coroutineContext[Job]
                                val lastWallActivityMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                                val lastClockActivityMs = java.util.concurrent.atomic.AtomicLong(now())
                                ActiveTransferSignalRegistry.registerHook(transfer.id) { signal ->
                                    val interruptedOutcome = when (signal) {
                                        ActiveTransferSignal.CANCEL -> TransferOutcome.Cancelled
                                        ActiveTransferSignal.PAUSE -> TransferOutcome.Paused
                                    }
                                    executionJob?.cancel(TransferInterruptedException(interruptedOutcome))
                                }
                                val heartbeat = launch {
                                    while (true) {
                                        delay((leaseDurationMs / 3L).coerceAtLeast(5_000L))
                                        detectInterruption(transfer.id)?.let { throw TransferInterruptedException(it) }
                                        val tick = now()
                                        if (queueDao.renewLease(transfer.id, leaseOwner, tick + leaseDurationMs, tick) != 1) {
                                            detectInterruption(transfer.id)?.let { throw TransferInterruptedException(it) }
                                            throw CancellationException("Transfer lease lost")
                                        }
                                    }
                                }
                                val stallWatchdog = if (stallTimeoutMs > 0L) {
                                    launch {
                                        val checkIntervalMs = (stallTimeoutMs / 4L).coerceIn(25L, 10_000L)
                                        while (true) {
                                            delay(checkIntervalMs)
                                            val wallIdle = System.currentTimeMillis() - lastWallActivityMs.get()
                                            val clockIdle = now() - lastClockActivityMs.get()
                                            if (wallIdle >= stallTimeoutMs || clockIdle >= stallTimeoutMs) {
                                                val secs = (stallTimeoutMs / 1000L).coerceAtLeast(1L)
                                                executionJob?.cancel(
                                                    TransferInterruptedException(
                                                        TransferOutcome.Retryable(
                                                            code = "STALL_TIMEOUT",
                                                            message = "Transfer stalled with no progress for ${secs}s"
                                                        )
                                                    )
                                                )
                                                break
                                            }
                                        }
                                    }
                                } else {
                                    null
                                }
                                var lastProgressPersistAt = 0L
                                var lastDbInterruptCheckAt = 0L
                                try {
                                    executor.execute(transfer, leaseOwner) { bytes, total, speed, eta ->
                                        lastWallActivityMs.set(System.currentTimeMillis())
                                        lastClockActivityMs.set(now())
                                        val inMemorySignal = ActiveTransferSignalRegistry.currentSignal(transfer.id)
                                        when (inMemorySignal) {
                                            ActiveTransferSignal.CANCEL -> throw TransferInterruptedException(TransferOutcome.Cancelled)
                                            ActiveTransferSignal.PAUSE -> throw TransferInterruptedException(TransferOutcome.Paused)
                                            null -> Unit
                                        }
                                        val tick = now()
                                        val isTerminalBytes = total > 0L && bytes >= total
                                        if (lastDbInterruptCheckAt == 0L || isTerminalBytes || tick < lastDbInterruptCheckAt || (tick - lastDbInterruptCheckAt) >= 400L) {
                                            lastDbInterruptCheckAt = tick
                                            detectInterruption(transfer.id)?.let { throw TransferInterruptedException(it) }
                                        }
                                        if (lastProgressPersistAt == 0L || isTerminalBytes || tick < lastProgressPersistAt || (tick - lastProgressPersistAt) >= 400L) {
                                            if (queueDao.renewLease(transfer.id, leaseOwner, tick + leaseDurationMs, tick) != 1) {
                                                detectInterruption(transfer.id)?.let { throw TransferInterruptedException(it) }
                                                error("Transfer lease lost")
                                            }
                                            if (queueDao.persistProgress(transfer.id, leaseOwner, bytes, total, speed, eta, tick) != 1) {
                                                detectInterruption(transfer.id)?.let { throw TransferInterruptedException(it) }
                                                error("Transfer progress write lost its lease")
                                            }
                                            lastProgressPersistAt = tick
                                            emitProgress(
                                                TransferProgressSnapshot(
                                                    transferId = transfer.id,
                                                    state = TransferState.RUNNING,
                                                    bytesTransferred = bytes,
                                                    totalBytes = total,
                                                    bytesPerSecond = speed,
                                                    etaSeconds = eta,
                                                    retryCount = transfer.attemptCount
                                                )
                                            )
                                        }
                                    }
                                } finally {
                                    ActiveTransferSignalRegistry.unregisterHook(transfer.id)
                                    stallWatchdog?.cancelAndJoin()
                                    heartbeat.cancelAndJoin()
                                }
                            }
                            val resolvedOutcome = detectInterruption(transfer.id) ?: outcome
                            applyOutcome(transfer, leaseOwner, resolvedOutcome)
                            processedCount.incrementAndGet()
                        } catch (interrupted: TransferInterruptedException) {
                            applyOutcome(transfer, leaseOwner, interrupted.outcome)
                            processedCount.incrementAndGet()
                        } catch (cancelled: CancellationException) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                val interrupted = detectInterruption(transfer.id)
                                if (interrupted != null && !isStopped()) {
                                    applyOutcome(transfer, leaseOwner, interrupted)
                                    processedCount.incrementAndGet()
                                } else {
                                    queueDao.returnToQueue(transfer.id, leaseOwner, now())
                                    throw cancelled
                                }
                            }
                        } catch (error: Exception) {
                            val interrupted = detectInterruption(transfer.id)
                            if (interrupted != null) {
                                applyOutcome(transfer, leaseOwner, interrupted)
                            } else {
                                runCatching {
                                    applyOutcome(
                                        transfer,
                                        leaseOwner,
                                        TransferOutcome.Retryable("UNEXPECTED", error.message ?: "Transfer failed", now() + 30_000L)
                                    )
                                }
                            }
                            processedCount.incrementAndGet()
                        } finally {
                            ActiveTransferSignalRegistry.unregisterHook(transfer.id)
                            ActiveTransferSignalRegistry.clear(transfer.id)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                queueDao.releaseLease(transfer.id, leaseOwner, now())
                            }
                        }
                    }
                }
            }
            workers.forEach { it.join() }
        }

        if (holdAutoBackups.get()) {
            runCatching { onBackupPolicyHold?.invoke(heldNeedsCharging.get(), heldNeedsWifi.get()) }
        }

        val currentNow = now()
        val hasMore = if (holdAutoBackups.get()) {
            queueDao.hasRunnableNonBackupTransfers(currentNow)
        } else {
            queueDao.hasRunnableTransfers(currentNow)
        }
        return if (isStopped()) DrainResult.Stopped else DrainResult.Drained(processedCount.get(), hasMore)
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
        const val DEFAULT_STALL_TIMEOUT_MS = 180_000L
    }

    private suspend fun detectInterruption(transferId: String): TransferOutcome? {
        when (ActiveTransferSignalRegistry.currentSignal(transferId)) {
            ActiveTransferSignal.CANCEL -> return TransferOutcome.Cancelled
            ActiveTransferSignal.PAUSE -> return TransferOutcome.Paused
            null -> Unit
        }
        val row = queueDao.currentRow(transferId) ?: return null
        return when {
            row.state in setOf(TransferState.CANCELLING.name, TransferState.CANCELLED.name) ||
                row.status == "CANCELLED" -> TransferOutcome.Cancelled
            row.state in setOf(TransferState.PAUSING.name, TransferState.PAUSED.name) ||
                row.status == "PAUSED" -> TransferOutcome.Paused
            else -> null
        }
    }

    private suspend fun applyOutcome(
        transfer: LocalTransfer,
        leaseOwner: String,
        outcome: TransferOutcome
    ) {
        var effectiveOutcome = outcome
        var recordedAttemptCount = transfer.attemptCount
        when (outcome) {
            TransferOutcome.Completed -> {
                transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.COMPLETED, "COMPLETED")
                runCatching { executor.onCompleted(transfer) }
                runCatching { onTerminalOutcome?.invoke(transfer, outcome) }
            }
            is TransferOutcome.Retryable -> {
                val nextAttemptCount = transfer.attemptCount + 1
                recordedAttemptCount = nextAttemptCount
                val retryLimit = runCatching { maxRetryCount() }.getOrDefault(Int.MAX_VALUE).coerceAtLeast(0)
                if (nextAttemptCount > retryLimit) {
                    val permanentMessage = "Retry limit ($retryLimit) exceeded: ${outcome.message}"
                    val permanent = TransferOutcome.Permanent("RETRY_LIMIT_EXCEEDED", permanentMessage, outcome.httpStatus)
                    effectiveOutcome = permanent
                    transition(
                        transfer,
                        leaseOwner,
                        TransferState.RUNNING,
                        TransferState.FAILED_PERMANENT,
                        "FAILED",
                        permanent.code,
                        permanent.message,
                        httpStatus = permanent.httpStatus,
                        attemptCount = nextAttemptCount
                    )
                    runCatching { onTerminalOutcome?.invoke(transfer.copy(attemptCount = nextAttemptCount), permanent) }
                } else {
                    transition(
                        transfer,
                        leaseOwner,
                        TransferState.RUNNING,
                        TransferState.FAILED_RETRYABLE,
                        "FAILED",
                        outcome.code,
                        outcome.message,
                        outcome.retryAt ?: retryAt(nextAttemptCount),
                        outcome.httpStatus,
                        attemptCount = nextAttemptCount
                    )
                }
            }
            is TransferOutcome.Permanent -> {
                val nextAttemptCount = transfer.attemptCount + 1
                recordedAttemptCount = nextAttemptCount
                transition(
                    transfer,
                    leaseOwner,
                    TransferState.RUNNING,
                    TransferState.FAILED_PERMANENT,
                    "FAILED",
                    outcome.code,
                    outcome.message,
                    httpStatus = outcome.httpStatus,
                    attemptCount = nextAttemptCount
                )
                runCatching { onTerminalOutcome?.invoke(transfer.copy(attemptCount = nextAttemptCount), outcome) }
            }
            is TransferOutcome.WaitingForNetwork -> transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_NETWORK, "PENDING", outcome.code, outcome.message)
            is TransferOutcome.WaitingForAuth -> {
                transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_AUTH, "PENDING", outcome.code, outcome.message)
                runCatching { onTerminalOutcome?.invoke(transfer, outcome) }
            }
            TransferOutcome.Paused -> transitionPausedDeterministic(transfer, leaseOwner)
            TransferOutcome.Cancelled -> {
                val latest = queueDao.currentRow(transfer.id) ?: transfer
                if (latest.leaseOwner == null || latest.leaseOwner == leaseOwner) {
                    runCatching { executor.onCancelled(latest) }
                }
                transitionCancelledDeterministic(transfer, leaseOwner)
            }
        }
        diagnostics?.recordEvent(
            operationType = "transfer",
            transferId = transfer.id,
            accountId = transfer.accountId,
            providerType = transfer.providerType,
            state = effectiveOutcome.diagnosticState(),
            retryCount = recordedAttemptCount,
            errorCode = effectiveOutcome.diagnosticCode(),
            httpStatus = effectiveOutcome.diagnosticHttpStatus(),
            checkpointAt = now(),
            workerRunId = leaseOwner
        )
    }

    private suspend fun transitionPausedDeterministic(transfer: LocalTransfer, leaseOwner: String) {
        val current = queueDao.currentRow(transfer.id)
        if (current != null && current.state == TransferState.QUEUED.name && (current.leaseOwner == leaseOwner || current.leaseOwner == null)) {
            // User already resumed the transfer while this worker was unwinding its pause.
            // Release the lease and keep the row in QUEUED so it resumes cleanly on the next iteration.
            queueDao.releaseLease(transfer.id, leaseOwner, now())
            return
        }
        if (current != null && current.state == TransferState.RUNNING.name && current.leaseOwner == leaseOwner) {
            transitionVia(transfer, leaseOwner, TransferState.RUNNING, TransferState.PAUSING, TransferState.PAUSED, "PAUSED")
        } else if (current != null && current.state == TransferState.PAUSING.name && current.leaseOwner == leaseOwner) {
            check(queueDao.transitionOwned(transfer.id, leaseOwner, TransferState.PAUSING.name, TransferState.PAUSED.name, "PAUSED", null, null, null, null, false, now()) == 1)
        } else {
            queueDao.finalizeInterrupted(
                id = transfer.id,
                ownerId = leaseOwner,
                finalState = TransferState.PAUSED.name,
                legacyStatus = "PAUSED",
                errorCode = null,
                errorMessage = null,
                now = now()
            )
        }
    }

    private suspend fun transitionCancelledDeterministic(transfer: LocalTransfer, leaseOwner: String) {
        val current = queueDao.currentRow(transfer.id)
        if (current != null && current.state == TransferState.RUNNING.name && current.leaseOwner == leaseOwner) {
            transitionVia(transfer, leaseOwner, TransferState.RUNNING, TransferState.CANCELLING, TransferState.CANCELLED, "CANCELLED")
        } else if (current != null && current.state == TransferState.CANCELLING.name && current.leaseOwner == leaseOwner) {
            check(queueDao.transitionOwned(transfer.id, leaseOwner, TransferState.CANCELLING.name, TransferState.CANCELLED.name, "CANCELLED", "USER_CANCELLED", "Cancelled", null, null, false, now()) == 1)
        } else {
            queueDao.finalizeInterrupted(
                id = transfer.id,
                ownerId = leaseOwner,
                finalState = TransferState.CANCELLED.name,
                legacyStatus = "CANCELLED",
                errorCode = "USER_CANCELLED",
                errorMessage = "Cancelled",
                now = now()
            )
        }
    }

    private suspend fun transition(
        transfer: LocalTransfer,
        leaseOwner: String,
        expected: TransferState,
        next: TransferState,
        legacyStatus: String,
        errorCode: String? = null,
        errorMessage: String? = null,
        nextAttemptAt: Long? = null,
        httpStatus: Int? = null,
        attemptCount: Int? = null
    ) {
        require(expected.canTransitionTo(next)) { "Illegal transfer transition $expected -> $next for ${transfer.id}" }
        check(queueDao.transitionOwned(transfer.id, leaseOwner, expected.name, next.name, legacyStatus, errorCode, errorMessage, nextAttemptAt, httpStatus, false, now(), attemptCount) == 1) {
            "Transfer lease lost before state transition"
        }
    }

    private suspend fun transitionVia(transfer: LocalTransfer, leaseOwner: String, from: TransferState, intermediate: TransferState, final: TransferState, legacyStatus: String) {
        require(from.canTransitionTo(intermediate) && intermediate.canTransitionTo(final))
        check(queueDao.transitionOwned(transfer.id, leaseOwner, from.name, intermediate.name, legacyStatus, null, null, null, null, true, now()) == 1)
        check(queueDao.transitionOwned(transfer.id, leaseOwner, intermediate.name, final.name, legacyStatus, null, null, null, null, false, now()) == 1)
    }

    private fun retryAt(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 10)
        val delayMs = (30_000L * (1L shl exponent)).coerceAtMost(6 * 60 * 60 * 1000L)
        return now() + delayMs
    }

    private fun TransferOutcome.diagnosticState(): String = when (this) {
        TransferOutcome.Completed -> TransferState.COMPLETED.name
        is TransferOutcome.Retryable -> TransferState.FAILED_RETRYABLE.name
        is TransferOutcome.Permanent -> TransferState.FAILED_PERMANENT.name
        is TransferOutcome.WaitingForNetwork -> TransferState.WAITING_FOR_NETWORK.name
        is TransferOutcome.WaitingForAuth -> TransferState.WAITING_FOR_AUTH.name
        TransferOutcome.Paused -> TransferState.PAUSED.name
        TransferOutcome.Cancelled -> TransferState.CANCELLED.name
    }

    private fun TransferOutcome.diagnosticCode(): String? = when (this) {
        is TransferOutcome.Retryable -> code
        is TransferOutcome.Permanent -> code
        is TransferOutcome.WaitingForNetwork -> code
        is TransferOutcome.WaitingForAuth -> code
        else -> null
    }

    private fun TransferOutcome.diagnosticHttpStatus(): Int? = when (this) {
        is TransferOutcome.Retryable -> httpStatus
        is TransferOutcome.Permanent -> httpStatus
        else -> null
    }
}


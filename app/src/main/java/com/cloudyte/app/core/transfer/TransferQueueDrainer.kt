package com.cloudyte.app.core.transfer

import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferQueueDao
import com.cloudyte.app.core.network.PolicyDecision
import com.cloudyte.app.core.network.TransferNetworkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

interface TransferOperationExecutor {
    suspend fun execute(transfer: LocalTransfer, leaseOwner: String, onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit): TransferOutcome
    suspend fun onCompleted(transfer: LocalTransfer) = Unit
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

/** Bounded queue consumer. A stale owner is fenced from writing progress or completion. */
class RoomTransferQueueDrainer(
    private val queueDao: TransferQueueDao,
    private val executor: TransferOperationExecutor,
    private val ownerId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val leaseDurationMs: Long = 2 * 60 * 1000L,
    private val maxTransfersPerDrain: Int = 3,
    private val networkGate: TransferNetworkGate? = null
) : TransferQueueDrainer {
    override suspend fun drain(
        trigger: DrainTrigger,
        isStopped: () -> Boolean,
        onProgress: suspend (TransferProgressSnapshot) -> Unit
    ): DrainResult {
        var processed = 0
        queueDao.recoverExpiredLeases(now())
        while (processed < maxTransfersPerDrain && !isStopped()) {
            val acquiredAt = now()
            val leaseOwner = "$ownerId:${UUID.randomUUID()}"
            val transfer = queueDao.acquireNextRunnableLease(leaseOwner, acquiredAt, acquiredAt + leaseDurationMs) ?: break
            try {
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
                        processed++
                        continue
                    }
                    is PolicyDecision.WaitingForAuth -> {
                        transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_AUTH, "PENDING", "AUTH_REQUIRED", decision.reason)
                        processed++
                        continue
                    }
                    PolicyDecision.Allowed, null -> Unit
                }
                val outcome = coroutineScope {
                    val heartbeat = launch {
                        while (true) {
                            delay((leaseDurationMs / 3L).coerceAtLeast(5_000L))
                            val tick = now()
                            if (queueDao.renewLease(transfer.id, leaseOwner, tick + leaseDurationMs, tick) != 1) {
                                throw CancellationException("Transfer lease lost")
                            }
                        }
                    }
                    try {
                        executor.execute(transfer, leaseOwner) { bytes, total, speed, eta ->
                            val tick = now()
                            check(queueDao.renewLease(transfer.id, leaseOwner, tick + leaseDurationMs, tick) == 1) { "Transfer lease lost" }
                            check(queueDao.persistProgress(transfer.id, leaseOwner, bytes, total, speed, eta, tick) == 1) { "Transfer progress write lost its lease" }
                            onProgress(TransferProgressSnapshot(transfer.id, TransferState.RUNNING, bytes, total, speed, eta, retryCount = transfer.attemptCount))
                        }
                    } finally {
                        heartbeat.cancelAndJoin()
                    }
                }
                when (outcome) {
                    TransferOutcome.Completed -> {
                        transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.COMPLETED, "COMPLETED")
                        runCatching { executor.onCompleted(transfer) }
                    }
                    is TransferOutcome.Retryable -> transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.FAILED_RETRYABLE, "FAILED", outcome.code, outcome.message, outcome.retryAt ?: retryAt(transfer.attemptCount), outcome.httpStatus)
                    is TransferOutcome.Permanent -> transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.FAILED_PERMANENT, "FAILED", outcome.code, outcome.message, httpStatus = outcome.httpStatus)
                    is TransferOutcome.WaitingForNetwork -> transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_NETWORK, "PENDING", outcome.code, outcome.message)
                    is TransferOutcome.WaitingForAuth -> transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.WAITING_FOR_AUTH, "PENDING", outcome.code, outcome.message)
                    TransferOutcome.Paused -> transitionVia(transfer, leaseOwner, TransferState.RUNNING, TransferState.PAUSING, TransferState.PAUSED, "PAUSED")
                    TransferOutcome.Cancelled -> transitionVia(transfer, leaseOwner, TransferState.RUNNING, TransferState.CANCELLING, TransferState.CANCELLED, "CANCELLED")
                }
                processed++
            } catch (cancelled: CancellationException) {
                queueDao.returnToQueue(transfer.id, leaseOwner, now())
                throw cancelled
            } catch (error: Exception) {
                transition(transfer, leaseOwner, TransferState.RUNNING, TransferState.FAILED_RETRYABLE, "FAILED", "UNEXPECTED", error.message ?: "Transfer failed", now() + 30_000L)
                processed++
            } finally {
                queueDao.releaseLease(transfer.id, leaseOwner, now())
            }
        }
        return if (isStopped()) DrainResult.Stopped else DrainResult.Drained(processed, queueDao.hasRunnableTransfers(now()))
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
        httpStatus: Int? = null
    ) {
        require(expected.canTransitionTo(next)) { "Illegal transfer transition $expected -> $next for ${transfer.id}" }
        check(queueDao.transitionOwned(transfer.id, leaseOwner, expected.name, next.name, legacyStatus, errorCode, errorMessage, nextAttemptAt, httpStatus, false, now()) == 1) {
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
}

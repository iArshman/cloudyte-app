package com.cloudyte.app.core.transfer

import android.content.Context
import com.cloudyte.app.domain.model.Transfer
import com.cloudyte.app.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow

/** Command boundary for lifecycle actions; background execution is delegated to the durable queue scheduler. */
class TransferCoordinator(
    context: Context,
    private val repository: TransferRepository,
    private val scheduleBackgroundWork: (Context) -> Unit,
    val diagnostics: TransferDiagnostics = TransferDiagnostics(),
    private val scheduler: TransferSchedulerContract? = null
) : TransferEngine {
    private val appContext = context.applicationContext

    fun observeTransfers(): Flow<List<Transfer>> = repository.observeAll()
    fun observeActiveTransfers(): Flow<List<Transfer>> = repository.observeActive()

    suspend fun dispatch(command: TransferCommand) {
        execute(command)
    }

    override suspend fun execute(command: TransferCommand): TransferExecutionState {
        diagnostics.record(command)
        return when (command) {
            is TransferCommand.Start -> {
                ActiveTransferSignalRegistry.clear(command.transferId)
                schedule()
                TransferExecutionState.Pending
            }
            is TransferCommand.Retry -> {
                ActiveTransferSignalRegistry.clear(command.transferId)
                repository.retry(command.transferId)
                schedule()
                TransferExecutionState.Pending
            }
            is TransferCommand.Pause -> {
                ActiveTransferSignalRegistry.signalPause(command.transferId)
                scheduler?.pauseActiveTransfer(command.transferId)
                repository.pause(command.transferId)
                TransferExecutionState.Paused
            }
            is TransferCommand.Resume -> {
                ActiveTransferSignalRegistry.clear(command.transferId)
                repository.resume(command.transferId)
                schedule()
                TransferExecutionState.Pending
            }
            is TransferCommand.Cancel -> {
                ActiveTransferSignalRegistry.signalCancel(command.transferId)
                scheduler?.cancelQueuedTransfer(command.transferId)
                repository.cancel(command.transferId)
                TransferExecutionState.Cancelled
            }
        }
    }

    private fun schedule() = scheduleBackgroundWork(appContext)
}


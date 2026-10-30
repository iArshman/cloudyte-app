package com.cloudyte.app.core.transfer

import android.content.Context
import com.cloudyte.app.domain.model.Transfer
import com.cloudyte.app.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single command boundary for transfer lifecycle operations.
 *
 * The current scheduler still executes the durable backup batch through
 * BackupWorker. This coordinator gives features one stable API while the
 * per-transfer worker implementation is introduced incrementally.
 */
class TransferCoordinator(
    context: Context,
    private val repository: TransferRepository,
    private val scheduleBackgroundWork: (Context) -> Unit,
    private val diagnostics: TransferDiagnostics = TransferDiagnostics()
) : TransferEngine {
    private val appContext = context.applicationContext

    fun observeTransfers(): Flow<List<Transfer>> = repository.observeAll()
    fun observeActiveTransfers(): Flow<List<Transfer>> = repository.observeActive()

    suspend fun dispatch(command: TransferCommand) {
        execute(command)
    }

    override suspend fun execute(command: TransferCommand): TransferState {
        diagnostics.record(command)
        when (command) {
            is TransferCommand.Start -> {
                schedule()
                return TransferState.Pending
            }
            is TransferCommand.Retry -> {
                repository.retry(command.transferId)
                schedule()
                return TransferState.Pending
            }
            is TransferCommand.Pause -> {
                repository.pause(command.transferId)
                return TransferState.Paused
            }
            is TransferCommand.Resume -> {
                repository.resume(command.transferId)
                schedule()
                return TransferState.Pending
            }
            is TransferCommand.Cancel -> {
                repository.cancel(command.transferId)
                return TransferState.Cancelled
            }
        }
    }

    private fun schedule() {
        scheduleBackgroundWork(appContext)
    }
}

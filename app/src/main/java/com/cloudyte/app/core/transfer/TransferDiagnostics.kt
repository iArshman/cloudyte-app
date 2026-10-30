package com.cloudyte.app.core.transfer

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

class TransferDiagnostics(
    private val tag: String = "TransferCoordinator"
) {
    private val started = AtomicInteger(0)
    private val retried = AtomicInteger(0)
    private val paused = AtomicInteger(0)
    private val resumed = AtomicInteger(0)
    private val cancelled = AtomicInteger(0)

    fun record(command: TransferCommand) {
        when (command) {
            is TransferCommand.Start -> started.incrementAndGet()
            is TransferCommand.Retry -> retried.incrementAndGet()
            is TransferCommand.Pause -> paused.incrementAndGet()
            is TransferCommand.Resume -> resumed.incrementAndGet()
            is TransferCommand.Cancel -> cancelled.incrementAndGet()
        }
        Log.i(tag, "transfer_command=${command::class.simpleName} transfer_id=${command.transferId}")
    }

    fun snapshot(): Snapshot = Snapshot(
        started = started.get(),
        retried = retried.get(),
        paused = paused.get(),
        resumed = resumed.get(),
        cancelled = cancelled.get()
    )

    data class Snapshot(
        val started: Int,
        val retried: Int,
        val paused: Int,
        val resumed: Int,
        val cancelled: Int
    )
}

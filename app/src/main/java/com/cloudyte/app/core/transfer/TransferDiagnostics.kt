package com.cloudyte.app.core.transfer

import android.content.Context
import com.cloudyte.app.BuildConfig
import com.cloudyte.app.core.common.CorrelationId
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Credential-free, bounded diagnostics retained only for local support export. */
class TransferDiagnostics(
    private val tag: String = "TransferCoordinator"
) {
    private val started = AtomicInteger(0)
    private val retried = AtomicInteger(0)
    private val paused = AtomicInteger(0)
    private val resumed = AtomicInteger(0)
    private val cancelled = AtomicInteger(0)
    private val events = ArrayDeque<DiagnosticEvent>()
    private val lock = Any()

    fun record(command: TransferCommand) {
        when (command) {
            is TransferCommand.Start -> started.incrementAndGet()
            is TransferCommand.Retry -> retried.incrementAndGet()
            is TransferCommand.Pause -> paused.incrementAndGet()
            is TransferCommand.Resume -> resumed.incrementAndGet()
            is TransferCommand.Cancel -> cancelled.incrementAndGet()
        }
        recordEvent(
            operationType = "command",
            transferId = command.transferId,
            state = command::class.simpleName ?: "unknown",
            correlationId = CorrelationId.newId()
        )
    }

    fun recordEvent(
        operationType: String,
        transferId: String? = null,
        accountId: String? = null,
        providerType: String? = null,
        state: String? = null,
        retryCount: Int = 0,
        errorCode: String? = null,
        httpStatus: Int? = null,
        checkpointAt: Long? = null,
        networkState: String? = null,
        workerRunId: String? = null,
        cacheCategory: String? = null,
        correlationId: String = CorrelationId.newId()
    ) {
        val event = DiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            operationType = operationType,
            transferId = transferId,
            accountHash = accountId?.let(::hash),
            providerType = providerType,
            state = state,
            retryCount = retryCount,
            errorCode = errorCode,
            httpStatus = httpStatus,
            checkpointAt = checkpointAt,
            networkState = networkState,
            workerRunId = workerRunId,
            cacheCategory = cacheCategory,
            correlationId = correlationId
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        started = started.get(),
        retried = retried.get(),
        paused = paused.get(),
        resumed = resumed.get(),
        cancelled = cancelled.get(),
        events = synchronized(lock) { events.toList() }
    )

    fun export(context: Context, databaseVersion: Int): String {
        val report = snapshot()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return buildString {
            appendLine("Cloudyte diagnostics")
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("build_number=${BuildConfig.VERSION_CODE}")
            appendLine("database_schema_version=$databaseVersion")
            appendLine("debug_build=${BuildConfig.DEBUG}")
            appendLine("exported_at=${format.format(Date())}")
            appendLine("locale=${Locale.getDefault().language}")
            appendLine("command_counts started=${report.started} retried=${report.retried} paused=${report.paused} resumed=${report.resumed} cancelled=${report.cancelled}")
            appendLine()
            report.events.forEach { event ->
                appendLine(event.toLine(format))
            }
        }
    }

    data class Snapshot(
        val started: Int,
        val retried: Int,
        val paused: Int,
        val resumed: Int,
        val cancelled: Int,
        val events: List<DiagnosticEvent>
    )

    data class DiagnosticEvent(
        val timestamp: Long,
        val operationType: String,
        val transferId: String?,
        val accountHash: String?,
        val providerType: String?,
        val state: String?,
        val retryCount: Int,
        val errorCode: String?,
        val httpStatus: Int?,
        val checkpointAt: Long?,
        val networkState: String?,
        val workerRunId: String?,
        val cacheCategory: String?,
        val correlationId: String
    ) {
        fun toLine(format: SimpleDateFormat): String = listOf(
            format.format(Date(timestamp)),
            "operation=$operationType",
            "transfer_id=${transferId ?: "-"}",
            "account_hash=${accountHash ?: "-"}",
            "provider=${providerType ?: "-"}",
            "state=${state ?: "-"}",
            "retry_count=$retryCount",
            "error=${errorCode ?: "-"}",
            "http_status=${httpStatus ?: "-"}",
            "checkpoint=${checkpointAt ?: "-"}",
            "network=${networkState ?: "-"}",
            "worker_run=${workerRunId ?: "-"}",
            "cache=${cacheCategory ?: "-"}",
            "correlation_id=$correlationId"
        ).joinToString(" ")
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EVENTS = 300
    }
}

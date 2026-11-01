package com.cloudyte.app.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.data.providers.UnsupportedProviderOperation
import com.cloudyte.app.data.providers.ProviderRegistry
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDirection
import com.cloudyte.app.ui.screens.UploadHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Executes persisted transfer rows through the account-selected provider/API path. */
class CloudTransferOperationExecutor(
    private val context: Context,
    private val database: AppDatabase,
    private val transfers: TransferRepository,
    private val providers: ProviderRegistry
) : TransferOperationExecutor {
    override suspend fun onCompleted(transfer: LocalTransfer) {
        val uri = Uri.parse(transfer.localUri ?: return)
        if (uri.scheme != "file") return
        val file = File(uri.path ?: return)
        val queueDirectory = File(context.filesDir, "queued-text").canonicalFile
        val canonical = file.canonicalFile
        if (canonical.name.startsWith("cloudyte-queued-text-") && canonical.parentFile == queueDirectory) {
            canonical.delete()
        }
    }

    override suspend fun execute(
        transfer: LocalTransfer,
        leaseOwner: String,
        onProgress: suspend (bytes: Long, total: Long, speed: Long, etaSeconds: Long?) -> Unit
    ): TransferOutcome = try {
        when (TransferDirection.valueOf(transfer.direction)) {
            TransferDirection.UPLOAD, TransferDirection.BACKUP -> upload(transfer, leaseOwner, onProgress)
            TransferDirection.DOWNLOAD -> download(transfer, onProgress)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HttpException) {
        when (val mapped = TransferErrorMapper.fromHttp(error.code())) {
            is TransferError.Authentication -> TransferOutcome.WaitingForAuth(mapped.code, error.message())
            is TransferError.Network -> TransferOutcome.WaitingForNetwork(mapped.code, error.message())
            else -> if (mapped.retryable) TransferOutcome.Retryable(mapped.code, error.message(), httpStatus = error.code())
            else TransferOutcome.Permanent(mapped.code, error.message(), httpStatus = error.code())
        }
    } catch (error: IOException) {
        TransferOutcome.Retryable("IO_FAILURE", error.message ?: "I/O failure")
    } catch (error: UnsupportedProviderOperation) {
        TransferOutcome.Permanent("PROVIDER_UNSUPPORTED_OPERATION", error.message ?: "Provider does not support this transfer operation")
    } catch (error: Exception) {
        TransferOutcome.Permanent("TRANSFER_EXECUTION_ERROR", error.message ?: "Transfer failed")
    }

    private suspend fun upload(
        transfer: LocalTransfer,
        leaseOwner: String,
        onProgress: suspend (Long, Long, Long, Long?) -> Unit
    ): TransferOutcome {
        val source = transfer.localUri ?: transfer.localPath
            ?: return TransferOutcome.Permanent("SOURCE_MISSING", "No local source URI was saved")
        val uri = Uri.parse(source)
        val size = resolveSize(uri, transfer.totalBytes)
        if (size < 0L) return TransferOutcome.Permanent("SOURCE_SIZE_UNKNOWN", "Could not determine source size")
        val accountType = transfer.providerType ?: database.accountDao().getAccountById(transfer.accountId)?.accountType
        val provider = providers.forAccountType(accountType)
        val progressCalculator = TransferProgressCalculator()
        val key = transfer.idempotencyKey ?: transfer.id
        val result = UploadHelper.uploadFileResumable(
            context = context,
            accountId = transfer.accountId,
            folderId = transfer.parentFolderId ?: transfer.remotePath,
            uri = uri,
            explicitFileName = transfer.fileName,
            explicitMimeType = "application/octet-stream",
            explicitFileSize = size,
            idempotencyKey = key,
            provider = provider,
            knownChecksum = transfer.checksum,
            onSessionReady = { sessionId, chunkSize, checksum, bytes, chunkIndex ->
                if (!transfers.saveResumeMetadataOwned(transfer.id, leaseOwner, sessionId, chunkSize, chunkIndex, checksum, key) ||
                    !transfers.updateResumableProgressOwned(transfer.id, leaseOwner, bytes, chunkIndex)
                ) throw CancellationException("Transfer lease lost before upload session checkpoint")
            },
            onProgress = { bytes, chunkIndex ->
                currentCoroutineContext().ensureActive()
                val metrics = progressCalculator.update(bytes, size)
                onProgress(bytes, size, metrics.bytesPerSecond, metrics.etaSeconds)
                if (!transfers.updateResumableProgressOwned(transfer.id, leaseOwner, bytes, chunkIndex)) {
                    throw CancellationException("Transfer lease lost before upload progress checkpoint")
                }
            }
        )
        return when {
            result.isSuccess -> TransferOutcome.Completed
            result.isIoError -> TransferOutcome.WaitingForNetwork("NETWORK_IO", result.errorMessage ?: "Network interrupted")
            result.statusCode == 401 -> TransferOutcome.WaitingForAuth("AUTH_REQUIRED", result.errorMessage ?: "Authentication required")
            result.isRetryable -> TransferOutcome.Retryable("UPLOAD_RETRYABLE", result.errorMessage ?: "Upload failed", httpStatus = result.statusCode.takeIf { it > 0 })
            else -> TransferOutcome.Permanent("UPLOAD_FAILED", result.errorMessage ?: "Upload failed", httpStatus = result.statusCode.takeIf { it > 0 })
        }
    }

    private suspend fun download(
        transfer: LocalTransfer,
        onProgress: suspend (Long, Long, Long, Long?) -> Unit
    ): TransferOutcome {
        val remoteId = transfer.remoteId ?: transfer.fileId
            ?: return TransferOutcome.Permanent("REMOTE_ID_MISSING", "No remote file ID was saved")
        val destination = transfer.localUri ?: transfer.localPath
            ?: return TransferOutcome.Permanent("DESTINATION_MISSING", "No local destination URI was saved")
        val body: ResponseBody = com.cloudyte.app.api.ApiClient.apiService.downloadFile(transfer.accountId, remoteId)
        body.use { response ->
            val uri = Uri.parse(destination)
            val output = if (uri.scheme == "content") {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: return TransferOutcome.Permanent("DESTINATION_UNAVAILABLE", "Could not open destination URI")
            } else {
                val path = if (uri.scheme == "file") uri.path else destination
                val file = File(path ?: return TransferOutcome.Permanent("DESTINATION_UNAVAILABLE", "Invalid destination path"))
                file.parentFile?.mkdirs()
                FileOutputStream(file)
            }
            val total = response.contentLength().coerceAtLeast(0L)
            response.byteStream().use { input -> output.use { out ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                val progressCalculator = TransferProgressCalculator()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    copied += count
                    val metrics = progressCalculator.update(copied, total)
                    onProgress(copied, total, metrics.bytesPerSecond, metrics.etaSeconds)
                }
                out.flush()
                if (total > 0L && copied != total) throw IOException("Download ended before the expected byte count")
            } } }
        return TransferOutcome.Completed
    }

    private fun resolveSize(uri: Uri, declared: Long): Long {
        if (declared > 0L) return declared
        if (uri.scheme == "file") return File(uri.path.orEmpty()).length()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
        catch (_: Exception) { -1L }
    }
}

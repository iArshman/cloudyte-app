package com.cloudyte.app.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.core.transfer.ResumableTransfer
import com.cloudyte.app.data.providers.CloudProvider
import com.cloudyte.app.data.providers.CloudyteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class UploadResult(
    val isSuccess: Boolean,
    val statusCode: Int = 0,
    val errorMessage: String? = null,
    val fileName: String = "",
    val isIoError: Boolean = false,
    val isPermanentFailure: Boolean = false
) {
    val isFatalClientError: Boolean
        get() = isPermanentFailure || (!isIoError && statusCode in PERMANENT_HTTP_CODES)

    val isRetryable: Boolean
        get() = !isSuccess && !isFatalClientError && statusCode != 401

    companion object {
        val PERMANENT_HTTP_CODES = setOf(400, 403, 404, 405, 409, 410, 413, 414, 415, 422, 507)
    }
}

private class ContentUriStreamRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType?,
    private val contentLengthBytes: Long = -1L
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = if (contentLengthBytes >= 0L) contentLengthBytes else -1L

    override fun writeTo(sink: BufferedSink) {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for URI: $uri")
        inputStream.source().use { source ->
            sink.writeAll(source)
        }
    }
}

private class StreamingChunkRequestBody(
    private val data: ByteArray,
    private val byteCount: Int,
    private val mediaType: MediaType?,
    private val chunkStartOffset: Long,
    private val onBytesWritten: (Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = byteCount.toLong()

    override fun writeTo(sink: BufferedSink) {
        val sliceSize = 32 * 1024
        var pos = 0
        var lastNotifiedAt = 0L
        while (pos < byteCount) {
            val toWrite = minOf(sliceSize, byteCount - pos)
            sink.write(data, pos, toWrite)
            pos += toWrite
            val now = System.currentTimeMillis()
            if (lastNotifiedAt == 0L || (now - lastNotifiedAt) >= 120L || pos >= byteCount) {
                lastNotifiedAt = now
                onBytesWritten(chunkStartOffset + pos.toLong())
            }
        }
    }
}

private class FileSliceChunkRequestBody(
    private val file: File,
    private val chunkStartOffset: Long,
    private val byteCount: Int,
    private val mediaType: MediaType?,
    private val onBytesWritten: (Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = byteCount.toLong()

    override fun writeTo(sink: BufferedSink) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(chunkStartOffset)
            val sliceBuffer = ByteArray(32 * 1024)
            var pos = 0
            var lastNotifiedAt = 0L
            while (pos < byteCount) {
                val toRead = minOf(sliceBuffer.size, byteCount - pos)
                val read = raf.read(sliceBuffer, 0, toRead)
                if (read < 0) throw IOException("Unexpected end of local file at offset ${chunkStartOffset + pos}")
                sink.write(sliceBuffer, 0, read)
                pos += read
                val now = System.currentTimeMillis()
                if (lastNotifiedAt == 0L || (now - lastNotifiedAt) >= 120L || pos >= byteCount) {
                    lastNotifiedAt = now
                    onBytesWritten(chunkStartOffset + pos.toLong())
                }
            }
        }
    }
}

object UploadHelper {
    suspend fun uploadFile(
        context: Context,
        accountId: String,
        folderId: String?,
        uri: Uri,
        explicitFileName: String? = null,
        explicitMimeType: String? = null,
        explicitFileSize: Long = -1L,
        idempotencyKey: String? = null,
        checksum: String? = null,
        uploadSession: String? = null
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            var safeFileName = explicitFileName?.takeIf { it.isNotBlank() } ?: "file"
            try {
                val contentResolver = context.contentResolver
                var resolvedName = explicitFileName
                var resolvedSize = explicitFileSize

                if (resolvedName.isNullOrBlank() || resolvedSize < 0L) {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            if (resolvedName.isNullOrBlank()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1) {
                                    resolvedName = cursor.getString(nameIndex)
                                }
                            }
                            if (resolvedSize < 0L) {
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                                    resolvedSize = cursor.getLong(sizeIndex)
                                }
                            }
                        }
                    }
                }
                safeFileName = resolvedName?.takeIf { it.isNotBlank() } ?: "file_${System.currentTimeMillis()}"

                val extension = safeFileName.substringAfterLast('.', "").lowercase()
                val mimeFromExt = if (extension.isNotBlank()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                } else {
                    null
                }
                val resolvedMimeType = explicitMimeType
                    ?: mimeFromExt
                    ?: contentResolver.getType(uri)
                    ?: "application/octet-stream"

                // Verify stream can be opened before starting request
                contentResolver.openInputStream(uri)?.close()
                    ?: return@withContext UploadResult(
                        isSuccess = false,
                        errorMessage = "Cannot open local file stream",
                        fileName = safeFileName,
                        isIoError = true
                    )

                val requestBody = ContentUriStreamRequestBody(
                    contentResolver = contentResolver,
                    uri = uri,
                    mediaType = resolvedMimeType.toMediaTypeOrNull(),
                    contentLengthBytes = resolvedSize
                )

                val filePart = MultipartBody.Part.createFormData("file", safeFileName, requestBody)
                val folderIdPart = if (!folderId.isNullOrBlank()) {
                    folderId.toRequestBody("text/plain".toMediaTypeOrNull())
                } else {
                    null
                }

                val response = ApiClient.apiService.uploadFile(
                    accountId = accountId,
                    folderIdQuery = if (!folderId.isNullOrBlank()) folderId else null,
                    file = filePart,
                    folderIdPart = folderIdPart,
                    parentIdPart = folderIdPart,
                    idempotencyKey = idempotencyKey,
                    checksum = checksum,
                    uploadSession = uploadSession
                )

                if (response.isSuccessful) {
                    UploadResult(isSuccess = true, statusCode = response.code(), fileName = safeFileName)
                } else {
                    val rawErr = response.errorBody()?.string()
                    val parsedErr = ApiClient.parseError(rawErr, response.code())
                    Log.e("UploadHelper", "Upload failed: HTTP ${response.code()}")
                    UploadResult(
                        isSuccess = false,
                        statusCode = response.code(),
                        errorMessage = "HTTP ${response.code()}: $parsedErr",
                        fileName = safeFileName,
                        isIoError = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Log.e("UploadHelper", "HTTP exception during upload: ${e.code()}")
                UploadResult(
                    isSuccess = false,
                    statusCode = e.code(),
                    errorMessage = "HTTP ${e.code()}: ${e.message()}",
                    fileName = safeFileName,
                    isIoError = false
                )
            } catch (e: IOException) {
                Log.e("UploadHelper", "Network I/O failure during upload")
                UploadResult(
                    isSuccess = false,
                    errorMessage = e.localizedMessage ?: e.message ?: "Network I/O error",
                    fileName = safeFileName,
                    isIoError = true
                )
            } catch (e: Exception) {
                Log.e("UploadHelper", "Unexpected upload failure: ${e::class.simpleName}")
                UploadResult(
                    isSuccess = false,
                    errorMessage = e.localizedMessage ?: e.message ?: "Unknown upload error",
                    fileName = safeFileName,
                    isIoError = e.cause is IOException
                )
            }
        }
    }

    private fun openUriStream(context: Context, uri: Uri): java.io.InputStream? {
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists() && file.isFile) file.inputStream() else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun parseResponseError(response: retrofit2.Response<*>, fallback: String): String {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        val code = response.code()
        val parsed = if (code > 0) ApiClient.parseError(raw, code) else raw.orEmpty()
        return when {
            parsed.isNotBlank() && !parsed.startsWith("Request failed (HTTP") -> parsed
            code > 0 -> "$fallback (HTTP $code)"
            else -> fallback
        }
    }

    suspend fun uploadFileResumable(
        context: Context,
        accountId: String,
        folderId: String?,
        uri: Uri,
        explicitFileName: String,
        explicitMimeType: String,
        explicitFileSize: Long,
        idempotencyKey: String,
        provider: CloudProvider = CloudyteProvider(ApiClient.apiService),
        knownChecksum: String? = null,
        isEncrypted: Boolean = false,
        encryptionFormatVersion: Int? = null,
        encryptionKeyId: String? = null,
        encryptedSizeBytes: Long? = null,
        encryptedMetadata: com.cloudyte.app.api.EncryptedMetadataEnvelope? = null,
        onSessionReady: suspend (String, Long, String, Long, Int) -> Unit = { _, _, _, _, _ -> },
        onProgress: suspend (Long, Int) -> Unit = { _, _ -> },
        onStreamProgress: suspend (Long) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            provider.requireResumableUpload()
            val totalBytes = explicitFileSize
            if (totalBytes < 0L) throw IOException("File size is required for resumable upload")
            val checksum = knownChecksum ?: run {
                val digest = MessageDigest.getInstance("SHA-256")
                openUriStream(context, uri)?.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) digest.update(buffer, 0, read)
                    }
                } ?: throw IOException("Cannot open local file stream")
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }
            val effectiveSessionMimeType = if (isEncrypted) "application/octet-stream" else explicitMimeType
            val sessionRequest = UploadSessionRequest(
                fileName = explicitFileName,
                opaqueRemoteName = if (isEncrypted) explicitFileName else null,
                mimeType = effectiveSessionMimeType,
                size = totalBytes,
                folderId = folderId,
                checksum = checksum,
                idempotencyKey = idempotencyKey,
                providerType = provider.type,
                desiredChunkSize = ResumableTransfer.DEFAULT_CHUNK_SIZE,
                isEncrypted = isEncrypted,
                encryptionFormatVersion = if (isEncrypted) (encryptionFormatVersion ?: 1) else null,
                encryptionKeyId = if (isEncrypted) encryptionKeyId else null,
                encryptedSizeBytes = if (isEncrypted) (encryptedSizeBytes ?: totalBytes) else null,
                encryptedMetadata = if (isEncrypted) encryptedMetadata else null
            )
            var sessionResponse = provider.createUploadSession(accountId, sessionRequest)
            if (!sessionResponse.isSuccessful || sessionResponse.body() == null) {
                return@withContext UploadResult(
                    false,
                    sessionResponse.code(),
                    parseResponseError(sessionResponse, "Could not create upload session"),
                    explicitFileName
                )
            }
            val createdSession = sessionResponse.body()!!
            // Creation is idempotent by logical transfer key; query the provider/server state
            // before resuming so local progress is never mistaken for committed bytes.
            val statusResponse = provider.queryUploadSession(createdSession.sessionId)
            if (!statusResponse.isSuccessful || statusResponse.body() == null) {
                return@withContext UploadResult(
                    false,
                    statusResponse.code(),
                    parseResponseError(statusResponse, "Could not reconcile upload session offset"),
                    explicitFileName
                )
            }
            var session = statusResponse.body()!!
            if (session.status.equals("expired", ignoreCase = true) || session.status.equals("UPLOAD_SESSION_EXPIRED", ignoreCase = true)) {
                // The server excludes expired sessions from idempotency lookup, so the same
                // key creates a fresh provider session without changing transfer identity.
                sessionResponse = provider.createUploadSession(accountId, sessionRequest)
                if (!sessionResponse.isSuccessful || sessionResponse.body() == null) {
                    return@withContext UploadResult(
                        false,
                        sessionResponse.code(),
                        parseResponseError(sessionResponse, "Could not renew expired upload session"),
                        explicitFileName
                    )
                }
                val renewed = provider.queryUploadSession(sessionResponse.body()!!.sessionId)
                if (!renewed.isSuccessful || renewed.body() == null || renewed.body()!!.status.equals("expired", ignoreCase = true)) {
                    return@withContext UploadResult(
                        false,
                        renewed.code(),
                        parseResponseError(renewed, "Could not reconcile renewed upload session"),
                        explicitFileName
                    )
                }
                session = renewed.body()!!
            }
            var offset = session.receivedBytes.coerceIn(0L, totalBytes)
            val directLocalFile = if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let(::File)?.takeIf { it.isFile }
            } else null
            val maxChunkCap = if (directLocalFile != null) 16L * 1024L * 1024L else 2L * 1024L * 1024L
            val chunkSize = session.chunkSize.coerceAtLeast(64L * 1024L).coerceAtMost(maxChunkCap)
            var nextChunkIndex = ((offset + chunkSize - 1L) / chunkSize).toInt()
            onSessionReady(session.sessionId, chunkSize, checksum, offset, nextChunkIndex)
            val chunkMediaType = effectiveSessionMimeType.toMediaTypeOrNull()
            if (directLocalFile != null) {
                val earlyFailure: UploadResult? = coroutineScope {
                    val streamChannel = Channel<Long>(Channel.CONFLATED)
                    val streamJob = launch {
                        for (streamedBytes in streamChannel) {
                            onStreamProgress(streamedBytes)
                        }
                    }
                    try {
                        while (offset < totalBytes) {
                            val expected = minOf(chunkSize, totalBytes - offset).toInt()
                            val maxLiveBytes = (offset + expected.toLong() - 1L).coerceAtLeast(offset)
                            val body = FileSliceChunkRequestBody(
                                file = directLocalFile,
                                chunkStartOffset = offset,
                                byteCount = expected,
                                mediaType = chunkMediaType,
                                onBytesWritten = { writtenTotal ->
                                    streamChannel.trySend(writtenTotal.coerceAtMost(maxLiveBytes))
                                }
                            )
                            val part = MultipartBody.Part.createFormData("chunk", explicitFileName, body)
                            val response = provider.uploadChunk(
                                sessionId = session.sessionId,
                                contentRange = "bytes $offset-${offset + expected - 1}/$totalBytes",
                                chunk = part
                            )
                            if (!response.isSuccessful || response.body() == null) {
                                return@coroutineScope UploadResult(
                                    false,
                                    response.code(),
                                    parseResponseError(response, "Chunk upload failed at offset $offset"),
                                    explicitFileName
                                )
                            }
                            val nextOffset = response.body()!!.nextOffset.coerceIn(0L, totalBytes)
                            if (nextOffset <= offset) throw IOException("Provider did not advance the upload offset")
                            offset = nextOffset
                            nextChunkIndex++
                            onProgress(offset, nextChunkIndex)
                        }
                        null
                    } finally {
                        streamChannel.close()
                        streamJob.join()
                    }
                }
                if (earlyFailure != null) {
                    return@withContext earlyFailure
                }
            } else {
                openUriStream(context, uri)?.use { input ->
                    var skipped = 0L
                    var skipDrainBuffer: ByteArray? = null
                    while (skipped < offset) {
                        val n = input.skip(offset - skipped)
                        if (n > 0L) {
                            skipped += n
                        } else {
                            val drainBuf = skipDrainBuffer ?: ByteArray(64 * 1024).also { skipDrainBuffer = it }
                            val toRead = minOf(drainBuf.size.toLong(), offset - skipped).toInt()
                            val read = input.read(drainBuf, 0, toRead)
                            if (read < 0) throw IOException("Could not seek to resumable upload offset $offset")
                            skipped += read
                        }
                    }
                    val chunkBuffer = ByteArray(minOf(chunkSize, totalBytes).coerceAtLeast(1L).toInt())
                    val earlyFailure: UploadResult? = coroutineScope {
                        val streamChannel = Channel<Long>(Channel.CONFLATED)
                        val streamJob = launch {
                            for (streamedBytes in streamChannel) {
                                onStreamProgress(streamedBytes)
                            }
                        }
                        try {
                            while (offset < totalBytes) {
                                val expected = minOf(chunkSize, totalBytes - offset).toInt()
                                var readTotal = 0
                                while (readTotal < expected) {
                                    val read = input.read(chunkBuffer, readTotal, expected - readTotal)
                                    if (read < 0) throw IOException("Unexpected end of local file")
                                    readTotal += read
                                }
                                val maxLiveBytes = (offset + readTotal.toLong() - 1L).coerceAtLeast(offset)
                                val body = StreamingChunkRequestBody(
                                    data = chunkBuffer,
                                    byteCount = readTotal,
                                    mediaType = chunkMediaType,
                                    chunkStartOffset = offset,
                                    onBytesWritten = { writtenTotal ->
                                        streamChannel.trySend(writtenTotal.coerceAtMost(maxLiveBytes))
                                    }
                                )
                                val part = MultipartBody.Part.createFormData("chunk", explicitFileName, body)
                                val response = provider.uploadChunk(
                                    sessionId = session.sessionId,
                                    contentRange = "bytes $offset-${offset + readTotal - 1}/$totalBytes",
                                    chunk = part
                                )
                                if (!response.isSuccessful || response.body() == null) {
                                    return@coroutineScope UploadResult(
                                        false,
                                        response.code(),
                                        parseResponseError(response, "Chunk upload failed at offset $offset"),
                                        explicitFileName
                                    )
                                }
                                val nextOffset = response.body()!!.nextOffset.coerceIn(0L, totalBytes)
                                if (nextOffset <= offset) throw IOException("Provider did not advance the upload offset")
                                offset = nextOffset
                                nextChunkIndex++
                                onProgress(offset, nextChunkIndex)
                            }
                            null
                        } finally {
                            streamChannel.close()
                            streamJob.join()
                        }
                    }
                    if (earlyFailure != null) {
                        return@withContext earlyFailure
                    }
                } ?: throw IOException("Cannot open local file stream")
            }
            val complete = provider.completeUploadSession(session.sessionId)
            if (complete.isSuccessful && complete.body()?.success == true) {
                UploadResult(true, complete.code(), fileName = explicitFileName)
            } else {
                UploadResult(
                    false,
                    complete.code(),
                    parseResponseError(complete, "Could not finalize upload session"),
                    explicitFileName
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            UploadResult(false, e.code(), "HTTP ${e.code()}: ${e.message()}", explicitFileName)
        } catch (e: IOException) {
            UploadResult(false, errorMessage = e.message ?: "Resumable upload I/O error", fileName = explicitFileName, isIoError = true)
        } catch (e: Exception) {
            UploadResult(false, errorMessage = e.message ?: "Resumable upload failed", fileName = explicitFileName, isIoError = e.cause is IOException)
        }
    }

    suspend fun uploadTextContent(
        context: Context,
        accountId: String,
        folderId: String?,
        fileName: String,
        content: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                tempFile = File(context.cacheDir, fileName)
                tempFile.writeText(content)
                val requestFile = tempFile.asRequestBody("text/plain".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", tempFile.name, requestFile)
                val folderIdPart = if (!folderId.isNullOrBlank()) {
                    folderId.toRequestBody("text/plain".toMediaTypeOrNull())
                } else {
                    null
                }
                val response = ApiClient.apiService.uploadFile(
                    accountId = accountId,
                    folderIdQuery = if (!folderId.isNullOrBlank()) folderId else null,
                    file = body,
                    folderIdPart = folderIdPart,
                    parentIdPart = folderIdPart
                )
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            } finally {
                try {
                    tempFile?.delete()
                } catch (_: Exception) {
                }
            }
        }
    }
}

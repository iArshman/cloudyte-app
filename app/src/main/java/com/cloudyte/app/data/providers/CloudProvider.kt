package com.cloudyte.app.data.providers

import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.UploadChunkResponse
import com.cloudyte.app.api.UploadCompleteResponse
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.api.UploadSessionResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response

/** Typed failure distinct from network/server errors. */
class UnsupportedProviderOperation(val providerType: String, val operation: String) :
    UnsupportedOperationException("Provider '$providerType' does not support $operation")

/**
 * Server-backed capability adapter boundary for Cloudyte's unified transfer protocol.
 * Concrete adapters declare upstream provider capabilities while delegating staging and
 * streaming operations through the Cloudyte API.
 */
interface CloudProvider {
    val type: String
    val capabilities: ProviderCapabilities
    suspend fun createUploadSession(accountId: String, request: UploadSessionRequest): Response<UploadSessionResponse>
    suspend fun queryUploadSession(sessionId: String): Response<UploadSessionResponse> =
        throw UnsupportedProviderOperation(type, "upload session offset query")
    suspend fun uploadChunk(sessionId: String, contentRange: String, chunk: MultipartBody.Part): Response<UploadChunkResponse>
    suspend fun completeUploadSession(sessionId: String): Response<UploadCompleteResponse>
    suspend fun cancelUploadSession(sessionId: String): Response<Unit> =
        throw UnsupportedProviderOperation(type, "upload session cancellation")
    suspend fun downloadFile(
        accountId: String,
        fileId: String,
        range: String? = null,
        preview: Int? = null
    ): Response<ResponseBody> =
        throw UnsupportedProviderOperation(type, "file download")

    fun requireResumableUpload() {
        if (!capabilities.supportsResumableUpload) throw UnsupportedProviderOperation(type, "resumable upload")
    }
}

abstract class ApiCloudProvider(private val api: CloudyteApi) : CloudProvider {
    override suspend fun createUploadSession(accountId: String, request: UploadSessionRequest) = api.createUploadSession(accountId, request)
    override suspend fun queryUploadSession(sessionId: String) = api.getUploadSession(sessionId)
    override suspend fun uploadChunk(sessionId: String, contentRange: String, chunk: MultipartBody.Part) = api.uploadChunk(sessionId, contentRange, chunk)
    override suspend fun completeUploadSession(sessionId: String) = api.completeUploadSession(sessionId)
    override suspend fun cancelUploadSession(sessionId: String) = api.cancelUploadSession(sessionId)
    override suspend fun downloadFile(
        accountId: String,
        fileId: String,
        range: String?,
        preview: Int?
    ): Response<ResponseBody> {
        val effectiveRange = if (capabilities.supportsResumableDownload) range else null
        return api.downloadFile(
            accountId = accountId,
            fileId = fileId,
            preview = preview,
            range = effectiveRange
        )
    }
}

class TelegramProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "telegram"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        supportsProviderNativeResumability = false,
        supportsProviderSessionCancellation = false,
        supportsResumableDownload = false
    )
}

class GoogleDriveProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "google_drive"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        supportsProviderNativeResumability = true,
        supportsResumableDownload = true
    )
}

class OneDriveProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "onedrive"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        supportsProviderNativeResumability = true,
        supportsProviderSessionCancellation = true,
        supportsResumableDownload = true
    )
}

class CloudyteProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "cloudyte"
    override val capabilities = ProviderCapabilities(
        supportsResumableUpload = true,
        supportsProviderNativeResumability = false,
        supportsProviderSessionCancellation = false,
        supportsResumableDownload = true
    )
}


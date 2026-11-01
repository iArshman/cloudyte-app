package com.cloudyte.app.data.providers

import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.UploadChunkResponse
import com.cloudyte.app.api.UploadCompleteResponse
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.api.UploadSessionResponse
import okhttp3.MultipartBody
import retrofit2.Response

/** Typed failure distinct from network/server errors. */
class UnsupportedProviderOperation(val providerType: String, val operation: String) :
    UnsupportedOperationException("Provider '$providerType' does not support $operation")

/** Provider boundary for Cloudyte's server-backed transfer protocol. */
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
}

class TelegramProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "telegram"
    override val capabilities = ProviderCapabilities(supportsResumableDownload = false)
}
class GoogleDriveProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "google_drive"
    override val capabilities = ProviderCapabilities(supportsResumableDownload = false)
}
class OneDriveProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "onedrive"
    override val capabilities = ProviderCapabilities(supportsResumableDownload = false)
}
class CloudyteProvider(api: CloudyteApi) : ApiCloudProvider(api) {
    override val type = "cloudyte"
    override val capabilities = ProviderCapabilities(supportsResumableDownload = false)
}

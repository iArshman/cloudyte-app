package com.cloudyte.app.data.providers

/** Capability declarations reflect the common server upload-session contract used by these adapters. */
data class ProviderCapabilities(
    val supportsFolders: Boolean = true,
    val supportsRename: Boolean = true,
    val supportsDelete: Boolean = true,
    val supportsMove: Boolean = true,
    val supportsCopy: Boolean = true,
    val supportsServerSideSearch: Boolean = false,
    val supportsRemoteThumbnails: Boolean = true,
    val supportsLocalThumbnailGeneration: Boolean = true,
    val supportsMultipartUpload: Boolean = true,
    /** Cloudyte server-side staging can resume regardless of the upstream provider protocol. */
    val supportsResumableUpload: Boolean = true,
    /** True only when the upstream provider upload session itself can be resumed after server restart. */
    val supportsProviderNativeResumability: Boolean = false,
    /** Supports aborting a persisted upstream provider session, beyond cancelling Cloudyte staging. */
    val supportsProviderSessionCancellation: Boolean = false,
    val supportsResumableDownload: Boolean = false,
    val supportsPauseResume: Boolean = true,
    val maxChunkBytes: Long? = null,
    val maxConcurrentRequests: Int = 1,
    val requiresForegroundForLongTransfer: Boolean = true
)

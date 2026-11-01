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
    val supportsResumableUpload: Boolean = true,
    val supportsResumableDownload: Boolean = false,
    val supportsPauseResume: Boolean = true,
    val maxChunkBytes: Long? = null,
    val maxConcurrentRequests: Int = 1,
    val requiresForegroundForLongTransfer: Boolean = true
)

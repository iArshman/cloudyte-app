package com.cloudyte.app.core.transfer

import java.io.File
import java.security.MessageDigest

data class UploadSession(
    val transferId: String,
    val providerSessionId: String?,
    val chunkSize: Long,
    val nextChunkIndex: Int,
    val checksum: String?,
    val idempotencyKey: String
)

object ResumableTransfer {
    const val DEFAULT_CHUNK_SIZE = 8L * 1024L * 1024L

    fun newIdempotencyKey(transferId: String): String = "cloudyte-$transferId"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE.toInt())
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun newSession(transferId: String, totalBytes: Long, checksum: String? = null): UploadSession =
        UploadSession(
            transferId = transferId,
            providerSessionId = null,
            chunkSize = DEFAULT_CHUNK_SIZE,
            nextChunkIndex = 0,
            checksum = checksum,
            idempotencyKey = newIdempotencyKey(transferId)
        )
}

package com.cloudyte.app.core.transfer

import android.content.Context
import java.io.File

interface ChunkStore {
    suspend fun write(transferId: String, chunkIndex: Int, bytes: ByteArray)
    suspend fun read(transferId: String, chunkIndex: Int): ByteArray?
    suspend fun delete(transferId: String, chunkIndex: Int)
    suspend fun clear(transferId: String)
}

class FileChunkStore(context: Context) : ChunkStore {
    private val root = File(context.applicationContext.filesDir, "transfer_chunks")

    private fun chunkFile(transferId: String, chunkIndex: Int): File =
        File(File(root, transferId), "$chunkIndex.part")

    override suspend fun write(transferId: String, chunkIndex: Int, bytes: ByteArray) {
        chunkFile(transferId, chunkIndex).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    override suspend fun read(transferId: String, chunkIndex: Int): ByteArray? =
        chunkFile(transferId, chunkIndex).takeIf(File::exists)?.readBytes()

    override suspend fun delete(transferId: String, chunkIndex: Int) {
        chunkFile(transferId, chunkIndex).delete()
    }

    override suspend fun clear(transferId: String) {
        File(root, transferId).deleteRecursively()
    }
}

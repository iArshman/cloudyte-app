package com.cloudyte.app.core.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PreviewStore(context: Context) {
    private val root = File(context.applicationContext.cacheDir, "cloudyte_previews").apply { mkdirs() }

    suspend fun get(key: String): File? = withContext(Dispatchers.IO) {
        File(root, safeName(key)).takeIf { it.isFile }
    }

    suspend fun put(key: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val target = File(root, safeName(key))
        val temp = File(root, ".${target.name}.tmp")
        temp.outputStream().use { it.write(bytes) }
        check(temp.renameTo(target)) { "Unable to commit preview cache entry" }
        target
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
    }

    suspend fun enforceQuota(maxBytes: Long = DEFAULT_QUOTA_BYTES) = withContext(Dispatchers.IO) {
        var total = root.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= maxBytes) return@withContext
        root.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
            if (total <= maxBytes) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val DEFAULT_QUOTA_BYTES = 512L * 1024L * 1024L
    }
}

package com.example.api

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object OfflineFileManager {
    private const val TAG = "OfflineFileManager"
    private const val OFFLINE_DIR_NAME = "offline_vault"

    private val _downloadedFiles = MutableStateFlow<Set<String>>(emptySet())
    val downloadedFiles: StateFlow<Set<String>> = _downloadedFiles.asStateFlow()

    // Map of fileId to download progress (0.0f .. 1.0f)
    private val _activeDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, Float>> = _activeDownloads.asStateFlow()

    private val downloadingSet = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        scope.launch {
            refreshDownloadedSet(context)
        }
    }

    private fun getVaultDir(context: Context): File {
        val dir = File(context.filesDir, OFFLINE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    fun getLocalFile(context: Context, accountId: String, fileId: String, fileName: String): File {
        val safeAcc = sanitizeFileName(accountId)
        val safeFid = sanitizeFileName(fileId)
        val safeName = sanitizeFileName(fileName)
        return File(getVaultDir(context), "${safeAcc}_${safeFid}_$safeName")
    }

    fun findOfflineFile(context: Context, accountId: String, fileId: String): File? {
        val safeAcc = sanitizeFileName(accountId)
        val safeFid = sanitizeFileName(fileId)
        val prefix = "${safeAcc}_${safeFid}_"
        val dir = getVaultDir(context)
        val matches = dir.listFiles { _, name -> name.startsWith(prefix) && !name.endsWith(".tmp") }
        val found = matches?.firstOrNull { it.exists() && it.length() > 0 }
        return found
    }

    fun isDownloaded(context: Context, accountId: String, fileId: String): Boolean {
        if (_downloadedFiles.value.contains(fileId)) return true
        val file = findOfflineFile(context, accountId, fileId)
        val exists = file != null && file.exists() && file.length() > 0
        if (exists && !_downloadedFiles.value.contains(fileId)) {
            _downloadedFiles.value = _downloadedFiles.value + fileId
        }
        return exists
    }

    fun isDownloading(fileId: String): Boolean {
        return downloadingSet.contains(fileId)
    }

    fun getDownloadProgress(fileId: String): Float? {
        return _activeDownloads.value[fileId]
    }

    fun refreshDownloadedSet(context: Context) {
        val dir = getVaultDir(context)
        val files = dir.listFiles { _, name -> !name.endsWith(".tmp") } ?: emptyArray()
        val set = mutableSetOf<String>()
        for (f in files) {
            if (f.length() > 0) {
                // filename is format: ${accountId}_${fileId}_${name}
                val parts = f.name.split("_")
                if (parts.size >= 3) {
                    val fid = parts[1]
                    set.add(fid)
                }
            }
        }
        _downloadedFiles.value = set
    }

    /**
     * Automatically downloads file for offline availability when viewed.
     * Non-blocking background coroutine.
     */
    fun autoCacheOnView(context: Context, accountId: String, file: CloudFile) {
        val fid = file.effectiveId
        if (fid.isEmpty() || file.isFolderItem) return
        if (isDownloaded(context, accountId, fid) || downloadingSet.contains(fid)) {
            return
        }

        scope.launch {
            try {
                downloadFile(context, accountId, file)
            } catch (e: Exception) {
                Log.w(TAG, "Auto cache on view failed for ${file.effectiveName}: ${e.message}")
            }
        }
    }

    /**
     * Downloads file to offline storage with progress tracking.
     */
    suspend fun downloadFile(
        context: Context,
        accountId: String,
        file: CloudFile,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val fid = file.effectiveId
        val fileName = file.effectiveName.ifBlank { "file_${fid}" }
        val targetFile = getLocalFile(context, accountId, fid, fileName)

        if (targetFile.exists() && targetFile.length() > 0) {
            _downloadedFiles.value = _downloadedFiles.value + fid
            onProgress?.invoke(1f)
            return@withContext Result.success(targetFile)
        }

        if (!downloadingSet.add(fid)) {
            // Already in progress
            return@withContext Result.failure(Exception("Download already in progress"))
        }

        _activeDownloads.value = _activeDownloads.value + (fid to 0f)

        val tempFile = File(getVaultDir(context), "${targetFile.name}.tmp")
        try {
            val downloadUrl = ApiClient.getFileUrl(accountId, fid, preview = false)
            val token = ApiClient.getToken()
            val requestBuilder = Request.Builder().url(downloadUrl)
            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw Exception("Download failed with HTTP ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty response body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 100 || progress >= 1f) {
                                lastUpdate = now
                                _activeDownloads.value = _activeDownloads.value + (fid to progress)
                                onProgress?.invoke(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    _downloadedFiles.value = _downloadedFiles.value + fid
                    _activeDownloads.value = _activeDownloads.value - fid
                    downloadingSet.remove(fid)
                    onProgress?.invoke(1f)
                    return@withContext Result.success(targetFile)
                } else {
                    throw Exception("Failed to rename temporary file to target")
                }
            } else {
                throw Exception("Downloaded file is empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $fileName", e)
            if (tempFile.exists()) tempFile.delete()
            _activeDownloads.value = _activeDownloads.value - fid
            downloadingSet.remove(fid)
            return@withContext Result.failure(e)
        }
    }

    fun deleteOfflineFile(context: Context, accountId: String, fileId: String): Boolean {
        val file = findOfflineFile(context, accountId, fileId)
        val deleted = file?.delete() ?: false
        _downloadedFiles.value = _downloadedFiles.value - fileId
        return deleted
    }

    fun getOfflineCacheSize(context: Context): Long {
        val dir = getVaultDir(context)
        var total = 0L
        dir.listFiles()?.forEach {
            if (it.isFile) total += it.length()
        }
        return total
    }

    fun getOfflineCount(context: Context): Int {
        val dir = getVaultDir(context)
        return dir.listFiles { _, name -> !name.endsWith(".tmp") }?.count { it.length() > 0 } ?: 0
    }

    fun clearAllOfflineCache(context: Context) {
        val dir = getVaultDir(context)
        dir.listFiles()?.forEach { it.delete() }
        _downloadedFiles.value = emptySet()
        _activeDownloads.value = emptyMap()
    }
}

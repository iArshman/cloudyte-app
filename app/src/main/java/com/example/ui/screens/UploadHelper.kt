package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

data class UploadResult(
    val isSuccess: Boolean,
    val statusCode: Int = 0,
    val errorMessage: String? = null,
    val fileName: String = ""
)

object UploadHelper {
    suspend fun uploadFile(
        context: Context,
        accountId: String,
        folderId: String?,
        uri: Uri,
        explicitFileName: String? = null,
        explicitMimeType: String? = null
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val contentResolver = context.contentResolver
                var resolvedName = explicitFileName
                if (resolvedName.isNullOrBlank()) {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                resolvedName = cursor.getString(nameIndex)
                            }
                        }
                    }
                }
                val safeFileName = resolvedName?.takeIf { it.isNotBlank() } ?: "file_${System.currentTimeMillis()}"

                // Determine MIME type accurately
                val extension = safeFileName.substringAfterLast('.', "").lowercase()
                val mimeFromExt = if (extension.isNotBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) else null
                val resolvedMimeType = explicitMimeType
                    ?: mimeFromExt
                    ?: contentResolver.getType(uri)
                    ?: "application/octet-stream"

                // Create a temporary file uniquely named to prevent collisions
                val uniquePrefix = UUID.randomUUID().toString().take(8)
                tempFile = File(context.cacheDir, "${uniquePrefix}_$safeFileName")

                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("UploadHelper", "Cannot open input stream for URI: $uri ($safeFileName)")
                    return@withContext UploadResult(
                        isSuccess = false,
                        errorMessage = "Cannot open local file stream",
                        fileName = safeFileName
                    )
                }

                val outputStream = FileOutputStream(tempFile)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.length() == 0L) {
                    Log.w("UploadHelper", "Copied file is 0 bytes for $safeFileName")
                }

                val requestFile = tempFile.asRequestBody(resolvedMimeType.toMediaTypeOrNull())
                val filePart = MultipartBody.Part.createFormData("file", safeFileName, requestFile)
                val folderIdPart = if (!folderId.isNullOrBlank()) folderId.toRequestBody("text/plain".toMediaTypeOrNull()) else null

                val response = ApiClient.apiService.uploadFile(
                    accountId = accountId,
                    folderIdQuery = if (!folderId.isNullOrBlank()) folderId else null,
                    file = filePart,
                    folderIdPart = folderIdPart,
                    parentIdPart = folderIdPart
                )

                if (response.isSuccessful) {
                    UploadResult(isSuccess = true, statusCode = response.code(), fileName = safeFileName)
                } else {
                    val rawErr = response.errorBody()?.string()
                    val parsedErr = ApiClient.parseError(rawErr, response.code())
                    Log.e("UploadHelper", "Upload failed: HTTP ${response.code()} $parsedErr (Raw: $rawErr)")
                    UploadResult(
                        isSuccess = false,
                        statusCode = response.code(),
                        errorMessage = "HTTP ${response.code()}: $parsedErr",
                        fileName = safeFileName
                    )
                }
            } catch (e: Exception) {
                Log.e("UploadHelper", "Exception during upload", e)
                UploadResult(
                    isSuccess = false,
                    errorMessage = e.localizedMessage ?: e.message ?: "Unknown upload error",
                    fileName = explicitFileName ?: "file"
                )
            } finally {
                try {
                    tempFile?.delete()
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun uploadTextContent(context: Context, accountId: String, folderId: String?, fileName: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                tempFile = File(context.cacheDir, fileName)
                tempFile.writeText(content)
                val requestFile = tempFile.asRequestBody("text/plain".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", tempFile.name, requestFile)
                val folderIdPart = if (!folderId.isNullOrBlank()) folderId.toRequestBody("text/plain".toMediaTypeOrNull()) else null
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
                try { tempFile?.delete() } catch (_: Exception) {}
            }
        }
    }
}

package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import okhttp3.RequestBody.Companion.toRequestBody

object UploadHelper {
    suspend fun uploadFile(context: Context, accountId: String, folderId: String?, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                var fileName = "uploaded_file"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                }

                val tempFile = File(context.cacheDir, fileName)
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(tempFile)
                inputStream?.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }

                val requestFile = tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", tempFile.name, requestFile)
                
                val folderIdBody = folderId?.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = ApiClient.apiService.uploadFile(accountId, body, folderIdBody)
                tempFile.delete()
                
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun uploadTextContent(context: Context, accountId: String, folderId: String?, fileName: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, fileName)
                tempFile.writeText(content)
                val requestFile = tempFile.asRequestBody("text/plain".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", tempFile.name, requestFile)
                val folderIdBody = folderId?.toRequestBody("text/plain".toMediaTypeOrNull())
                val response = ApiClient.apiService.uploadFile(accountId, body, folderIdBody)
                tempFile.delete()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}

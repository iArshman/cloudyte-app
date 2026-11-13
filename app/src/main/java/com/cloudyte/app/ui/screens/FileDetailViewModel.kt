package com.cloudyte.app.ui.screens

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.api.ShareRequest
import com.cloudyte.app.core.security.EncryptedSharingNotSupportedException
import com.cloudyte.app.core.transfer.TransferCommand
import com.cloudyte.app.db.TransferDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CloudyteApplication
    private val cloudFileRepository = app.container.cloudFileRepository
    private val transferRepository = app.container.transferRepository
    private val transferCoordinator = app.container.transferCoordinator

    private val _file = MutableStateFlow<CloudFile?>(null)
    val file: StateFlow<CloudFile?> = _file.asStateFlow()

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _isProcessingCryptoAction = MutableStateFlow(false)
    val isProcessingCryptoAction: StateFlow<Boolean> = _isProcessingCryptoAction.asStateFlow()

    fun loadFile(fileId: String) {
        _file.value = FileRepository.getFile(fileId)
        viewModelScope.launch {
            val dbFile = cloudFileRepository.getFileById(fileId)
            if (dbFile != null) {
                _file.value = dbFile
            }
        }
    }

    fun shareFile(accountId: String, fileId: String) {
        viewModelScope.launch {
            val current = _file.value ?: cloudFileRepository.getFileById(fileId)
            if (current?.isEncrypted == true) {
                val ex = EncryptedSharingNotSupportedException()
                _shareError.value = ex.message ?: "Encrypted sharing is not supported"
                return@launch
            }
            try {
                val response = ApiClient.apiService.shareFile(ShareRequest(accountId, fileId))
                _shareUrl.value = response.effectiveUrl.ifEmpty {
                    "${ApiClient.API_BASE_URL}/api/download/$accountId/$fileId"
                }
            } catch (e: Exception) {
                Log.e("FileDetailViewModel", "Share error", e)
                _shareUrl.value = "${ApiClient.API_BASE_URL}/api/download/$accountId/$fileId"
            }
        }
    }

    fun clearShareError() {
        _shareError.value = null
    }

    fun downloadOrDecryptExportFile(
        accountId: String,
        targetFile: CloudFile,
        onFeedback: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isProcessingCryptoAction.value = true
            try {
                val context = getApplication<Application>()
                val resolvedAccId = targetFile.effectiveAccountId.ifBlank {
                    accountId.takeIf { it.isNotBlank() && it != "all" && it != "root" }.orEmpty()
                }
                if (targetFile.isEncrypted) {
                    val decryptedResult = withContext(Dispatchers.IO) {
                        OfflineFileManager.prepareDecryptedFileForViewer(context, resolvedAccId, targetFile)
                    }
                    decryptedResult.fold(
                        onSuccess = { decryptedFile ->
                            val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                                ?: File(context.filesDir, "exports").apply { mkdirs() }
                            exportDir.mkdirs()
                            val safeName = targetFile.effectiveName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "decrypted_file" }
                            val destFile = File(exportDir, safeName)
                            withContext(Dispatchers.IO) {
                                decryptedFile.inputStream().use { input ->
                                    destFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            onFeedback("Decrypted and exported: ${destFile.name}")
                        },
                        onFailure = { err ->
                            onFeedback(err.message ?: "Failed to decrypt file")
                        }
                    )
                } else {
                    val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: File(context.filesDir, "downloads").apply { mkdirs() }
                    exportDir.mkdirs()
                    val safeName = targetFile.effectiveName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "download" }
                    val destFile = File(exportDir, safeName)
                    val transferId = transferRepository.enqueueTransfer(
                        accountId = resolvedAccId,
                        fileId = targetFile.effectiveId,
                        fileName = targetFile.effectiveName,
                        localPath = destFile.absolutePath,
                        direction = TransferDirection.DOWNLOAD,
                        totalBytes = targetFile.sizeBytes,
                        isEncrypted = false
                    )
                    transferCoordinator.dispatch(TransferCommand.Start(transferId))
                    onFeedback("Download added to transfers")
                }
            } catch (e: Exception) {
                onFeedback(e.message ?: "Download failed")
            } finally {
                _isProcessingCryptoAction.value = false
            }
        }
    }

    fun createEncryptedCopy(
        accountId: String,
        targetFile: CloudFile,
        onFeedback: (String) -> Unit
    ) {
        if (targetFile.isEncrypted) {
            onFeedback("File is already encrypted")
            return
        }
        viewModelScope.launch {
            _isProcessingCryptoAction.value = true
            try {
                val context = getApplication<Application>()
                val resolvedAccId = targetFile.effectiveAccountId.ifBlank {
                    accountId.takeIf { it.isNotBlank() && it != "all" && it != "root" }.orEmpty()
                }
                val plaintextFileResult = withContext(Dispatchers.IO) {
                    OfflineFileManager.prepareDecryptedFileForViewer(context, resolvedAccId, targetFile)
                }
                plaintextFileResult.fold(
                    onSuccess = { localPlaintextFile ->
                        val transferId = transferRepository.enqueueTransfer(
                            accountId = resolvedAccId,
                            fileName = targetFile.effectiveName,
                            localPath = Uri.fromFile(localPlaintextFile).toString(),
                            remotePath = targetFile.path ?: targetFile.location,
                            direction = TransferDirection.UPLOAD,
                            totalBytes = localPlaintextFile.length(),
                            isEncrypted = true,
                            originalName = targetFile.effectiveName,
                            originalMimeType = targetFile.mimeType,
                            originalSize = localPlaintextFile.length()
                        )
                        transferCoordinator.dispatch(TransferCommand.Start(transferId))
                        onFeedback("Encrypted copy queued for upload")
                    },
                    onFailure = { err ->
                        onFeedback(err.message ?: "Could not prepare file for encryption")
                    }
                )
            } catch (e: Exception) {
                onFeedback(e.message ?: "Could not queue encrypted copy")
            } finally {
                _isProcessingCryptoAction.value = false
            }
        }
    }

    fun renameFile(accountId: String, fileId: String, newName: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = cloudFileRepository.rename(fileId, accountId, newName)
            if (result.isSuccess) {
                _file.value = cloudFileRepository.getFileById(fileId)
                onSuccess()
            } else {
                Log.e("FileDetailViewModel", "Rename could not be queued", result.exceptionOrNull())
            }
        }
    }

    fun deleteFile(accountId: String, fileId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val result = cloudFileRepository.delete(fileId, accountId)
                if (result.isSuccess) onSuccess()
                else Log.e("FileDetailViewModel", "Delete could not be queued", result.exceptionOrNull())
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun clearShareUrl() {
        _shareUrl.value = null
    }
}

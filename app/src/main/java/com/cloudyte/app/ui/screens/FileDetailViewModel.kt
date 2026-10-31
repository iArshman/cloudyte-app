package com.cloudyte.app.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.ShareRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val cloudFileRepository = (application as CloudyteApplication).container.cloudFileRepository
    private val _file = MutableStateFlow<CloudFile?>(null)
    val file: StateFlow<CloudFile?> = _file.asStateFlow()

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    fun loadFile(fileId: String) {
        _file.value = FileRepository.getFile(fileId)
    }

    fun shareFile(accountId: String, fileId: String) {
        viewModelScope.launch {
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

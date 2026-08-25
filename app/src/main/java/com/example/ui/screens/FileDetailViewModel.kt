package com.example.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.api.ShareRequest
import com.example.api.RenameRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileDetailViewModel : ViewModel() {
    private val _file = MutableStateFlow<CloudFile?>(null)
    val file: StateFlow<CloudFile?> = _file.asStateFlow()

    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    fun loadFile(fileId: String) {
        val cached = FileRepository.getFile(fileId)
        _file.value = cached
    }

    fun shareFile(accountId: String, fileId: String) {
        viewModelScope.launch {
            try {
                val response = ApiClient.apiService.shareFile(ShareRequest(accountId, fileId))
                _shareUrl.value = response.effectiveUrl.ifEmpty { "${ApiClient.API_BASE_URL}/api/download/$accountId/$fileId" }
            } catch (e: Exception) {
                Log.e("FileDetailViewModel", "Share error", e)
                _shareUrl.value = "${ApiClient.API_BASE_URL}/api/download/$accountId/$fileId"
            }
        }
    }

    fun renameFile(accountId: String, fileId: String, newName: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = ApiClient.apiService.renameFile(fileId, accountId, RenameRequest(newName))
                if (res.isSuccessful) {
                    val updated = _file.value?.copy(name = newName)
                    _file.value = updated
                    if (updated != null) {
                        FileRepository.cacheFiles(listOf(updated))
                    }
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("FileDetailViewModel", "Rename error", e)
            }
        }
    }

    fun deleteFile(accountId: String, fileId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                val res = ApiClient.apiService.deleteFile(fileId, accountId)
                if (res.isSuccessful) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("FileDetailViewModel", "Delete error", e)
            } finally {
                _isDeleting.value = false
            }
        }
    }

    fun clearShareUrl() {
        _shareUrl.value = null
    }
}

package com.example.api

import com.example.db.AppDatabase
import kotlinx.coroutines.runBlocking

object FileRepository {
    private val filesCache = mutableMapOf<String, CloudFile>()
    private var activeList = listOf<CloudFile>()
    private var activeAccountId: String? = null

    fun cacheFiles(files: List<CloudFile>) {
        files.forEach { 
            val fid = it.effectiveId
            if (fid.isNotEmpty()) {
                filesCache[fid] = it
            }
        }
    }

    fun setActiveList(files: List<CloudFile>, accountId: String? = null) {
        activeList = files.filter { it.isFolder != true }
        if (accountId != null) {
            activeAccountId = accountId
        }
        cacheFiles(files)
    }

    fun getActiveList(): List<CloudFile> {
        if (activeList.isNotEmpty()) {
            return activeList
        }
        val db = AppDatabase.getInstance()
        if (db != null) {
            try {
                val dbFiles = runBlocking {
                    val acc = activeAccountId
                    if (acc != null) {
                        db.cloudFileDao().getFilesForAccount(acc)
                    } else {
                        db.cloudFileDao().getAllFiles()
                    }
                }
                val cloudFiles = dbFiles.map { it.toCloudFile() }.filter { !it.isFolderItem }
                if (cloudFiles.isNotEmpty()) {
                    activeList = cloudFiles
                    cacheFiles(cloudFiles)
                }
            } catch (_: Exception) {
            }
        }
        return activeList
    }

    fun getActiveAccountId(): String? = activeAccountId

    fun getFile(id: String): CloudFile? {
        val cached = filesCache[id]
        if (cached != null) return cached
        val db = AppDatabase.getInstance()
        if (db != null) {
            try {
                val local = runBlocking {
                    db.cloudFileDao().getFileById(id)
                }
                if (local != null) {
                    val file = local.toCloudFile()
                    filesCache[id] = file
                    return file
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}

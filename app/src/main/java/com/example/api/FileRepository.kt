package com.example.api

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
        return activeList
    }

    fun getActiveAccountId(): String? = activeAccountId

    fun getFile(id: String): CloudFile? {
        return filesCache[id]
    }
}

package com.cloudyte.app.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.R
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.core.transfer.TransferCommand
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.TransferDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Android Storage Access Framework (SAF) [DocumentsProvider] that exposes connected Cloudyte
 * drives directly inside the system file picker (Open / Attach / Save As across all Android apps).
 */
class CloudyteDocumentsProvider : DocumentsProvider() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val closeHandlerThread by lazy {
        HandlerThread("CloudyteSafWriteClose").apply { start() }
    }
    private val closeHandler by lazy {
        Handler(closeHandlerThread.looper)
    }

    override fun onCreate(): Boolean = true

    private fun requireAppContext(): Context {
        return context?.applicationContext
            ?: throw IllegalStateException("CloudyteDocumentsProvider context is unavailable")
    }

    private fun database(): AppDatabase = AppDatabase.getDatabase(requireAppContext())

    private fun cloudFileRepository(): CloudFileRepository {
        val ctx = requireAppContext()
        return (ctx as? CloudyteApplication)?.container?.cloudFileRepository
            ?: CloudFileRepository(database(), ApiClient.apiService)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveRootProjection(projection))
        val ctx = requireAppContext()
        val db = database()

        val accounts = runBlocking(Dispatchers.IO) {
            val cached = db.accountDao().getAllAccounts()
            if (cached.isNotEmpty()) {
                cached
            } else if (!ApiClient.getToken().isNullOrBlank()) {
                val remote = withTimeoutOrNull(4_000L) {
                    runCatching { ApiClient.apiService.getAccounts() }.getOrNull()
                }.orEmpty()
                if (remote.isNotEmpty()) {
                    val entities = remote.map { LocalAccount.fromAccount(it) }
                    db.accountDao().insertAccounts(entities)
                    entities
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

        for (account in accounts) {
            val driveTitle = account.name.ifBlank { account.driveName?.takeIf { it.isNotBlank() } ?: "Drive" }
            val summary = account.email?.takeIf { it.isNotBlank() } ?: account.accountType
            val row = cursor.newRow()
            row.add(Root.COLUMN_ROOT_ID, buildRootId(account.id))
            row.add(Root.COLUMN_DOCUMENT_ID, buildDocumentId(account.id, ROOT_FOLDER_ID))
            row.add(Root.COLUMN_TITLE, "Cloudyte ($driveTitle)")
            row.add(Root.COLUMN_SUMMARY, summary)
            row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            row.add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_SEARCH or
                    Root.FLAG_SUPPORTS_IS_CHILD
            )
            row.add(Root.COLUMN_MIME_TYPES, "*/*")
            if (account.totalBytes > 0L) {
                row.add(Root.COLUMN_CAPACITY_BYTES, account.totalBytes)
                row.add(
                    Root.COLUMN_AVAILABLE_BYTES,
                    (account.totalBytes - account.usageBytes).coerceAtLeast(0L)
                )
            } else {
                row.add(Root.COLUMN_CAPACITY_BYTES, null)
                row.add(Root.COLUMN_AVAILABLE_BYTES, null)
            }
        }

        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val parsed = parseDocumentId(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")
        val db = database()

        runBlocking(Dispatchers.IO) {
            if (parsed.itemId == ROOT_FOLDER_ID) {
                val account = db.accountDao().getAllAccounts().firstOrNull { it.id == parsed.accountId }
                val title = account?.name?.ifBlank { account.driveName ?: "Cloudyte" } ?: "Cloudyte"
                val row = cursor.newRow()
                row.add(Document.COLUMN_DOCUMENT_ID, documentId)
                row.add(Document.COLUMN_DISPLAY_NAME, title)
                row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                row.add(Document.COLUMN_SIZE, 0L)
                row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            } else {
                val localFile = findLocalFile(db, parsed.accountId, parsed.itemId)
                    ?: throw FileNotFoundException("Document not found: $documentId")
                addFileRow(cursor, parsed.accountId, localFile)
            }
        }

        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val parsed = parseDocumentId(parentDocumentId)
            ?: throw FileNotFoundException("Invalid parentDocumentId: $parentDocumentId")
        val db = database()
        val repo = cloudFileRepository()
        val folderKey = if (parsed.itemId.isBlank()) ROOT_FOLDER_ID else parsed.itemId

        runBlocking(Dispatchers.IO) {
            if (!ApiClient.getToken().isNullOrBlank()) {
                withTimeoutOrNull(4_500L) {
                    runCatching { repo.refresh(parsed.accountId, folderKey, force = false) }
                }
            }
            val children = db.cloudFileDao().getFilesForFolder(parsed.accountId, folderKey)
            for (child in children) {
                addFileRow(cursor, parsed.accountId, child)
            }
        }

        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val accountId = parseRootId(rootId)
            ?: throw FileNotFoundException("Invalid rootId: $rootId")
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return cursor

        val db = database()
        val repo = cloudFileRepository()

        runBlocking(Dispatchers.IO) {
            val localMatches = runCatching { repo.searchFiles(accountId, trimmedQuery) }
                .getOrDefault(emptyList())
                .map { LocalCloudFile.fromCloudFile(it, accountId, ROOT_FOLDER_ID) }
            val dbMatches = db.cloudFileDao().searchFiles(accountId, trimmedQuery)
            val combined = (dbMatches + localMatches).distinctBy { it.id }
            for (item in combined) {
                addFileRow(cursor, accountId, item)
            }
        }

        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true
        val parent = parseDocumentId(parentDocumentId) ?: return false
        val child = parseDocumentId(documentId) ?: return false
        if (parent.accountId != child.accountId) return false
        if (parent.itemId == ROOT_FOLDER_ID) return true

        val db = database()
        return runBlocking(Dispatchers.IO) {
            var currentId: String? = child.itemId
            val visited = mutableSetOf<String>()
            while (!currentId.isNullOrBlank() && currentId != ROOT_FOLDER_ID && visited.add(currentId)) {
                val row = findLocalFile(db, child.accountId, currentId) ?: return@runBlocking false
                val folderId = row.folderId.ifBlank { ROOT_FOLDER_ID }
                if (folderId == parent.itemId) return@runBlocking true
                currentId = folderId
            }
            false
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parsed = parseDocumentId(parentDocumentId)
            ?: throw FileNotFoundException("Invalid parentDocumentId: $parentDocumentId")
        val folderKey = if (parsed.itemId.isBlank()) ROOT_FOLDER_ID else parsed.itemId
        val cleanName = displayName.trim().ifBlank { "Untitled" }
        val db = database()
        val repo = cloudFileRepository()

        return runBlocking(Dispatchers.IO) {
            if (mimeType == Document.MIME_TYPE_DIR) {
                val res = repo.createFolder(cleanName, parsed.accountId, folderKey)
                if (res.isFailure) {
                    throw IOException(res.exceptionOrNull()?.message ?: "Failed to create folder")
                }
                val created = db.cloudFileDao().getFilesForFolder(parsed.accountId, folderKey)
                    .firstOrNull { it.isFolder && it.name == cleanName }
                    ?: throw IOException("Created folder not found in local index")
                buildDocumentId(parsed.accountId, created.id)
            } else {
                val pendingId = "$SAF_PENDING_PREFIX${UUID.randomUUID()}"
                val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.US)
                val nowSeconds = System.currentTimeMillis() / 1000L
                val placeholder = LocalCloudFile(
                    id = pendingId,
                    name = cleanName,
                    type = ext.ifBlank { "file" },
                    size = "0 B",
                    rawSize = 0L,
                    time = nowSeconds,
                    updatedAt = System.currentTimeMillis(),
                    isFolder = false,
                    accountId = parsed.accountId,
                    folderId = folderKey
                )
                db.cloudFileDao().insertFiles(listOf(placeholder))
                buildDocumentId(parsed.accountId, pendingId)
            }
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val parsed = parseDocumentId(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")
        if (parsed.itemId == ROOT_FOLDER_ID) {
            throw UnsupportedOperationException("Cannot rename root directory")
        }
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) throw IllegalArgumentException("Display name cannot be blank")

        val repo = cloudFileRepository()
        runBlocking(Dispatchers.IO) {
            val res = repo.rename(parsed.itemId, parsed.accountId, cleanName)
            if (res.isFailure) {
                throw IOException(res.exceptionOrNull()?.message ?: "Failed to rename document")
            }
        }
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        val parsed = parseDocumentId(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")
        if (parsed.itemId == ROOT_FOLDER_ID) {
            throw UnsupportedOperationException("Cannot delete root directory")
        }
        val repo = cloudFileRepository()
        runBlocking(Dispatchers.IO) {
            val res = repo.delete(parsed.itemId, parsed.accountId)
            if (res.isFailure) {
                throw IOException(res.exceptionOrNull()?.message ?: "Failed to delete document")
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val parsed = parseDocumentId(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")
        if (parsed.itemId == ROOT_FOLDER_ID) {
            throw FileNotFoundException("Cannot open root folder as a file stream")
        }

        val ctx = requireAppContext()
        val db = database()
        val isWrite = mode.contains('w')

        if (isWrite) {
            val localRow = runBlocking(Dispatchers.IO) {
                findLocalFile(db, parsed.accountId, parsed.itemId)
            } ?: throw FileNotFoundException("Target document not found: $documentId")

            val stagingDir = File(ctx.cacheDir, "saf_upload_staging").apply { mkdirs() }
            val stagingFile = File(stagingDir, "saf_${UUID.randomUUID()}_${localRow.name}")
            if (!stagingFile.exists()) stagingFile.createNewFile()

            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(stagingFile, pfdMode, closeHandler) { closeError ->
                if (closeError != null || !stagingFile.exists()) {
                    stagingFile.delete()
                    return@open
                }
                ioScope.launch {
                    try {
                        val size = stagingFile.length().coerceAtLeast(0L)
                        db.cloudFileDao().insertFiles(
                            listOf(
                                localRow.copy(
                                    rawSize = size,
                                    size = com.cloudyte.app.api.formatByteCount(size),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        )
                        val app = ctx as? CloudyteApplication ?: return@launch
                        val prefs = runCatching { UserPreferences.observe(ctx).first() }.getOrNull()
                        val encryptUploads = prefs?.isUploadEncryptionEnabled == true
                        val resolvedMime = resolveMimeType(localRow)
                        val remoteFolder = localRow.folderId.takeUnless { it == ROOT_FOLDER_ID || it.isBlank() }
                        val transferId = app.container.transferRepository.enqueueTransfer(
                            accountId = parsed.accountId,
                            fileName = localRow.name,
                            localPath = Uri.fromFile(stagingFile).toString(),
                            remotePath = remoteFolder,
                            direction = TransferDirection.UPLOAD,
                            totalBytes = size,
                            isEncrypted = encryptUploads,
                            originalName = if (encryptUploads) localRow.name else null,
                            originalMimeType = if (encryptUploads) resolvedMime else null,
                            originalSize = if (encryptUploads) size else null
                        )
                        app.container.transferCoordinator.dispatch(TransferCommand.Start(transferId))
                    } catch (_: Exception) {
                        stagingFile.delete()
                    }
                }
            }
        }

        // Read mode ("r")
        val localRow = runBlocking(Dispatchers.IO) {
            findLocalFile(db, parsed.accountId, parsed.itemId)
        } ?: throw FileNotFoundException("Document not found: $documentId")

        val cloudFile = localRow.toCloudFile()
        val offlineVaultFile = OfflineFileManager.findOfflineFile(ctx, parsed.accountId, parsed.itemId)

        if (cloudFile.isEncrypted || offlineVaultFile != null) {
            val readableFile = runBlocking(Dispatchers.IO) {
                OfflineFileManager.prepareDecryptedFileForViewer(ctx, parsed.accountId, cloudFile)
            }.getOrElse { err ->
                throw FileNotFoundException(err.message ?: "Unable to prepare cloud document")
            }
            return ParcelFileDescriptor.open(readableFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Stream directly over a reliable pipe from Cloudyte API
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val downloadUrl = ApiClient.getCleanDownloadUrl(parsed.accountId, parsed.itemId, preview = false)
        val call = ApiClient.okHttpClient.newCall(Request.Builder().url(downloadUrl).get().build())

        signal?.setOnCancelListener {
            call.cancel()
        }

        ioScope.launch {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        writeEnd.closeWithError("HTTP ${response.code}")
                        return@use
                    }
                    val body = response.body ?: run {
                        writeEnd.closeWithError("Empty response body")
                        return@use
                    }
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                if (signal?.isCanceled == true) {
                                    throw IOException("Document read cancelled")
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { writeEnd.closeWithError(e.message ?: "Stream interrupted") }
            } finally {
                runCatching { writeEnd.close() }
            }
        }

        return readEnd
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val parsed = parseDocumentId(documentId)
            ?: throw FileNotFoundException("Invalid documentId: $documentId")
        val db = database()
        val thumb = runBlocking(Dispatchers.IO) {
            db.thumbnailDao().getForAccount(parsed.accountId)
                .firstOrNull { it.remoteId == parsed.itemId }
        }
        val thumbFile = thumb?.path?.takeIf { it.isNotBlank() }?.let(::File)
        if (thumbFile != null && thumbFile.isFile && thumbFile.length() > 0L) {
            val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0L, thumbFile.length())
        }
        val pfd = openDocument(documentId, "r", signal)
        return AssetFileDescriptor(pfd, 0L, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private suspend fun findLocalFile(
        db: AppDatabase,
        accountId: String,
        itemId: String
    ): LocalCloudFile? {
        return db.cloudFileDao().getFilesForAccount(accountId).firstOrNull { it.id == itemId }
            ?: db.cloudFileDao().getFileById(itemId)?.takeIf {
                it.accountId == null || it.accountId == accountId
            }
    }

    private fun addFileRow(
        cursor: MatrixCursor,
        accountId: String,
        localFile: LocalCloudFile
    ) {
        val cloudFile: CloudFile = localFile.toCloudFile()
        val docId = buildDocumentId(accountId, localFile.id)
        val displayName = cloudFile.effectiveName.ifBlank { localFile.name }
        val mimeType = if (localFile.isFolder) {
            Document.MIME_TYPE_DIR
        } else {
            resolveMimeType(localFile, cloudFile)
        }
        val sizeBytes = if (localFile.isFolder) {
            0L
        } else {
            (cloudFile.plaintextSizeBytes ?: localFile.rawSize.takeIf { it > 0L } ?: cloudFile.sizeBytes).coerceAtLeast(0L)
        }
        val rawTime = localFile.updatedAt ?: localFile.time ?: 0L
        val lastModifiedMs = when {
            rawTime <= 0L -> System.currentTimeMillis()
            rawTime < 100_000_000_000L -> rawTime * 1000L
            else -> rawTime
        }
        var flags = if (localFile.isFolder) {
            Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_WRITE
        }
        if (!localFile.isFolder && (cloudFile.isImage || cloudFile.isVideo)) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docId)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        row.add(Document.COLUMN_LAST_MODIFIED, lastModifiedMs)
        row.add(Document.COLUMN_SIZE, sizeBytes)
        row.add(Document.COLUMN_FLAGS, flags)
    }

    private fun resolveMimeType(
        localFile: LocalCloudFile,
        cloudFile: CloudFile = localFile.toCloudFile()
    ): String {
        if (localFile.isFolder) return Document.MIME_TYPE_DIR
        val nameExt = cloudFile.effectiveName.substringAfterLast('.', "").lowercase(Locale.US)
        val typeExt = cloudFile.effectiveType.lowercase(Locale.US)
        val candidateExt = nameExt.ifBlank { typeExt }
        if (candidateExt.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(candidateExt)?.let { return it }
        }
        return when {
            cloudFile.isImage -> "image/*"
            cloudFile.isVideo -> "video/*"
            cloudFile.isAudio -> "audio/*"
            cloudFile.isPdf -> "application/pdf"
            cloudFile.isTextOrCode -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val ROOT_FOLDER_ID = "root"
        private const val ROOT_PREFIX = "root:"
        private const val DOC_PREFIX = "doc:"
        private const val SAF_PENDING_PREFIX = "saf_pending_"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_CAPACITY_BYTES,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS
        )

        data class ParsedDocumentId(
            val accountId: String,
            val itemId: String
        )

        fun resolveRootProjection(projection: Array<out String>?): Array<out String> =
            projection ?: DEFAULT_ROOT_PROJECTION

        fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> =
            projection ?: DEFAULT_DOCUMENT_PROJECTION

        fun buildRootId(accountId: String): String = "$ROOT_PREFIX${Uri.encode(accountId)}"

        fun parseRootId(rootId: String): String? {
            if (!rootId.startsWith(ROOT_PREFIX)) return null
            val encoded = rootId.removePrefix(ROOT_PREFIX)
            if (encoded.isBlank()) return null
            return Uri.decode(encoded)
        }

        fun buildDocumentId(accountId: String, itemId: String): String {
            val normalizedItem = itemId.ifBlank { ROOT_FOLDER_ID }
            return "$DOC_PREFIX${Uri.encode(accountId)}:${Uri.encode(normalizedItem)}"
        }

        fun parseDocumentId(documentId: String): ParsedDocumentId? {
            if (!documentId.startsWith(DOC_PREFIX)) return null
            val payload = documentId.removePrefix(DOC_PREFIX)
            val separatorIdx = payload.indexOf(':')
            if (separatorIdx <= 0 || separatorIdx >= payload.length - 1) return null
            val accountId = Uri.decode(payload.substring(0, separatorIdx))
            val itemId = Uri.decode(payload.substring(separatorIdx + 1))
            if (accountId.isBlank() || itemId.isBlank()) return null
            return ParsedDocumentId(accountId = accountId, itemId = itemId)
        }

        fun notifyRootsChanged(context: Context) {
            runCatching {
                val authority = "${context.packageName}.documents"
                val rootsUri = DocumentsContract.buildRootsUri(authority)
                context.contentResolver.notifyChange(rootsUri, null)
            }
        }
    }
}

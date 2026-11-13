package com.cloudyte.app.core.transfer

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.LoginRequest
import com.cloudyte.app.api.LoginResponse
import com.cloudyte.app.api.MoveCopyRequest
import com.cloudyte.app.api.OAuthLinkResponse
import com.cloudyte.app.api.PublicSettingsResponse
import com.cloudyte.app.api.RegisterRequest
import com.cloudyte.app.api.RenameRequest
import com.cloudyte.app.api.ShareRequest
import com.cloudyte.app.api.ShareResponse
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.TgDriveRegisterRequest
import com.cloudyte.app.api.UploadChunkResponse
import com.cloudyte.app.api.UploadCompleteResponse
import com.cloudyte.app.api.UploadSessionRequest
import com.cloudyte.app.api.UploadSessionResponse
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.api.UserSettingsRequest
import com.cloudyte.app.core.cache.ThumbnailStore
import com.cloudyte.app.core.security.Base64UrlCodec
import com.cloudyte.app.core.security.ContentKeyManager
import com.cloudyte.app.core.security.CryptoConstants
import com.cloudyte.app.core.security.EncryptedMetadataCodec
import com.cloudyte.app.core.security.JvmMemoryMasterKeyWrapper
import com.cloudyte.app.core.security.KeyBackupCodec
import com.cloudyte.app.core.security.PlaintextFileMetadata
import com.cloudyte.app.core.security.SecureFileCleanup
import com.cloudyte.app.core.security.StreamFileCrypto
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.data.providers.ProviderRegistry
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.TransferDirection
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class EncryptedTransferIntegrationTest {

    private lateinit var context: Application
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase
    private lateinit var keyManager: ContentKeyManager
    private lateinit var streamCrypto: StreamFileCrypto
    private lateinit var metadataCodec: EncryptedMetadataCodec
    private lateinit var keyBackupCodec: KeyBackupCodec
    private lateinit var fakeApi: FakeCloudyteApi
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var transferRepo: TransferRepository
    private lateinit var cloudFileRepo: CloudFileRepository
    private lateinit var executor: CloudTransferOperationExecutor

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "integration_${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        db.accountDao().insertAccounts(
            listOf(
                LocalAccount(
                    id = "acc_1",
                    name = "Test Drive",
                    driveName = "Test Drive",
                    email = "user@example.com",
                    accountType = "cloudyte",
                    usageBytes = 0L,
                    totalBytes = 10_000_000L,
                    percentage = 0f,
                    fileCount = 0,
                    isDefault = true,
                    channelId = null
                )
            )
        )

        keyManager = ContentKeyManager(
            cryptoDir = File(tempDir, "crypto"),
            masterKeyWrapper = JvmMemoryMasterKeyWrapper()
        )
        streamCrypto = StreamFileCrypto()
        metadataCodec = EncryptedMetadataCodec()
        keyBackupCodec = KeyBackupCodec(keyManager)
        fakeApi = FakeCloudyteApi()
        providerRegistry = ProviderRegistry(fakeApi)
        transferRepo = TransferRepository(db.transferDao(), providerRegistry)
        cloudFileRepo = CloudFileRepository(
            db = db,
            api = fakeApi,
            contentKeyProvider = keyManager,
            metadataCodec = metadataCodec
        )
        executor = CloudTransferOperationExecutor(
            context = context,
            database = db,
            transfers = transferRepo,
            providers = providerRegistry,
            contentKeyProvider = keyManager,
            streamFileCrypto = streamCrypto,
            metadataCodec = metadataCodec,
            shouldEncryptNewUploads = { false },
            shouldEncryptOfflineFiles = { false }
        )
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private suspend fun executeWithLease(transferId: String, leaseOwner: String): TransferOutcome {
        val current = db.transferDao().getById(transferId)!!
        val leased = current.copy(
            state = "RUNNING",
            status = "RUNNING",
            leaseOwner = leaseOwner,
            leaseExpiresAt = System.currentTimeMillis() + 60_000L
        )
        db.transferDao().insert(leased)
        return executor.execute(leased, leaseOwner) { _, _, _, _ -> }
    }

    @Test
    fun `encrypted upload sends only opaque filename, encrypted bytes, encrypted checksum, and encrypted metadata`() = runBlocking {
        val originalName = "top_secret_contract_2026.pdf"
        val originalBytes = "This is confidential plaintext contract content that must never reach the server.".toByteArray()
        val sourceFile = File(tempDir, originalName)
        sourceFile.writeBytes(originalBytes)
        val plaintextSha256 = streamCrypto.calculateFileSha256(sourceFile)

        val transferId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileName = originalName,
            localPath = Uri.fromFile(sourceFile).toString(),
            remotePath = "root",
            direction = TransferDirection.UPLOAD,
            totalBytes = originalBytes.size.toLong(),
            isEncrypted = true,
            originalName = originalName,
            originalMimeType = "application/pdf",
            originalSize = originalBytes.size.toLong()
        )

        val outcome = executeWithLease(transferId, "test-owner")
        assertTrue("Upload should succeed: $outcome", outcome is TransferOutcome.Completed)

        val req = fakeApi.lastUploadSessionRequest
        assertNotNull(req)
        // Server receives opaque filename cyte_<random>.bin, never original filename
        assertTrue("Filename must start with cyte_: ${req!!.fileName}", req.fileName.startsWith("cyte_"))
        assertTrue("Filename must end with .bin: ${req.fileName}", req.fileName.endsWith(".bin"))
        assertFalse(req.fileName.contains("top_secret"))
        assertFalse(req.fileName.contains(".pdf"))

        // Server receives generic application/octet-stream
        assertEquals(CryptoConstants.ENCRYPTED_MIME_TYPE, req.mimeType)

        // Server receives encrypted size and SHA-256 of encrypted bytes (never plaintext hash)
        val uploadedBytes = fakeApi.uploadedBytesBySession[fakeApi.lastSessionId]!!
        assertEquals(uploadedBytes.size.toLong(), req.size)
        assertEquals(uploadedBytes.size.toLong(), req.encryptedSizeBytes)

        val uploadedTemp = File(tempDir, "uploaded_check.bin").apply { writeBytes(uploadedBytes) }
        val uploadedEncryptedSha256 = streamCrypto.calculateFileSha256(uploadedTemp)
        assertEquals(uploadedEncryptedSha256, req.checksum)
        assertFalse("Server must never receive plaintext hash", req.checksum == plaintextSha256)

        // Uploaded bytes must be valid CYTE1 format and must not contain plaintext substring
        assertTrue(streamCrypto.hasCyteMagic(uploadedTemp))
        assertFalse(String(uploadedBytes, Charsets.ISO_8859_1).contains("confidential plaintext"))

        // Server receives encryptionFormatVersion = 1, opaque keyId, and encryptedMetadata
        assertEquals(true, req.isEncrypted)
        assertEquals(CryptoConstants.FORMAT_VERSION_V1, req.encryptionFormatVersion)
        val activeKey = keyManager.getExistingContentKey()
        assertEquals(activeKey.keyId, req.encryptionKeyId)
        val rawKeyBase64 = Base64UrlCodec.encode(activeKey.copyKeyBytes())
        assertFalse("Key ID must never contain raw key material", req.encryptionKeyId!!.contains(rawKeyBase64))
        assertNotNull(req.encryptedMetadata)

        // Decrypt uploaded bytes using activeKey and verify exact plaintext match
        val decOut = ByteArrayOutputStream()
        streamCrypto.decryptStream(ByteArrayInputStream(uploadedBytes), decOut, activeKey)
        assertArrayEquals(originalBytes, decOut.toByteArray())
        activeKey.destroy()

        // Complete cleanup removes staging file
        executor.onCompleted(db.transferDao().getById(transferId)!!)
        val stagingFile = File(SecureFileCleanup.getEncryptedStagingDir(context), "$transferId.cyte")
        assertFalse("Staging file must be deleted on completion", stagingFile.exists())
    }

    @Test
    fun `resumable encrypted upload reuses valid staging file and recreates corrupted staging file`() = runBlocking {
        val originalBytes = ByteArray(150_000).also { SecureRandom().nextBytes(it) }
        val sourceFile = File(tempDir, "resume_test.bin").apply { writeBytes(originalBytes) }
        val keyMaterial = keyManager.getOrCreateContentKey()

        val transferId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileName = "resume_test.bin",
            localPath = Uri.fromFile(sourceFile).toString(),
            remotePath = "root",
            direction = TransferDirection.UPLOAD,
            totalBytes = originalBytes.size.toLong(),
            isEncrypted = true
        )

        // Pre-create a valid staging file and persist its metadata in transfer row
        val encResult = streamCrypto.encryptUriToStagingFile(
            context = context,
            sourceUri = Uri.fromFile(sourceFile),
            transferId = transferId,
            keyMaterial = keyMaterial
        )
        val firstStagingBytes = encResult.stagingFile.readBytes()
        val env = metadataCodec.encryptMetadata(
            PlaintextFileMetadata(
                originalName = "resume_test.bin",
                originalMimeType = "application/octet-stream",
                originalExtension = "bin",
                plaintextSizeBytes = originalBytes.size.toLong(),
                plaintextChecksum = encResult.plaintextSha256,
                createdAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis()
            ),
            keyMaterial
        )
        transferRepo.saveEncryptionMetadata(
            id = transferId,
            isEncrypted = true,
            encryptionFormatVersion = CryptoConstants.FORMAT_VERSION_V1,
            encryptionKeyId = keyMaterial.keyId,
            encryptedMetadata = metadataCodec.serializeEnvelopeToJson(env),
            encryptedSizeBytes = encResult.encryptedSizeBytes,
            encryptedChecksum = encResult.encryptedSha256,
            opaqueRemoteName = encResult.opaqueRemoteName,
            encryptedStagePath = encResult.stagingFile.absolutePath,
            plaintextSizeBytes = originalBytes.size.toLong(),
            originalMetadataAvailable = true
        )

        // Execute upload and verify it reused the exact same staging ciphertext
        val outcome1 = executeWithLease(transferId, "owner-1")
        assertTrue(outcome1 is TransferOutcome.Completed)
        val uploadedBytes1 = fakeApi.uploadedBytesBySession[fakeApi.lastSessionId]!!
        assertArrayEquals("Valid staging file must be reused on retry", firstStagingBytes, uploadedBytes1)

        // Now test corrupted staging file is recreated with fresh encryption
        val transferId2 = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileName = "resume_test.bin",
            localPath = Uri.fromFile(sourceFile).toString(),
            remotePath = "root",
            direction = TransferDirection.UPLOAD,
            totalBytes = originalBytes.size.toLong(),
            isEncrypted = true
        )
        val stagingFile2 = File(SecureFileCleanup.getEncryptedStagingDir(context), "$transferId2.cyte")
        stagingFile2.writeBytes(firstStagingBytes)
        stagingFile2.appendBytes(byteArrayOf(0x01, 0x02, 0x03))
        transferRepo.saveEncryptionMetadata(
            id = transferId2,
            isEncrypted = true,
            encryptionFormatVersion = CryptoConstants.FORMAT_VERSION_V1,
            encryptionKeyId = keyMaterial.keyId,
            encryptedMetadata = metadataCodec.serializeEnvelopeToJson(env),
            encryptedSizeBytes = encResult.encryptedSizeBytes,
            encryptedChecksum = encResult.encryptedSha256,
            opaqueRemoteName = encResult.opaqueRemoteName,
            encryptedStagePath = stagingFile2.absolutePath,
            plaintextSizeBytes = originalBytes.size.toLong(),
            originalMetadataAvailable = true
        )

        val outcome2 = executeWithLease(transferId2, "owner-1")
        assertTrue(outcome2 is TransferOutcome.Completed)
        val uploadedBytes2 = fakeApi.uploadedBytesBySession[fakeApi.lastSessionId]!!
        val decOut = ByteArrayOutputStream()
        streamCrypto.decryptStream(ByteArrayInputStream(uploadedBytes2), decOut, keyMaterial)
        assertArrayEquals(originalBytes, decOut.toByteArray())
        keyMaterial.destroy()
    }

    @Test
    fun `encrypted download validates CYTE1 and decrypts to destination, and corrupted download fails permanently`() = runBlocking {
        val keyMaterial = keyManager.getOrCreateContentKey()
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }
        val encOut = ByteArrayOutputStream()
        val encRes = streamCrypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encOut,
            keyMaterial = keyMaterial,
            plaintextSize = plaintext.size.toLong(),
            chunkSize = CryptoConstants.MIN_CHUNK_SIZE_BYTES
        )
        val validCiphertext = encOut.toByteArray()
        fakeApi.downloadFiles["enc_file_1"] = validCiphertext

        val destFile = File(tempDir, "downloaded_decrypted.bin")
        val transferId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileId = "enc_file_1",
            fileName = "downloaded_decrypted.bin",
            localPath = destFile.absolutePath,
            direction = TransferDirection.DOWNLOAD,
            totalBytes = validCiphertext.size.toLong(),
            isEncrypted = true,
            encryptionFormatVersion = CryptoConstants.FORMAT_VERSION_V1,
            encryptionKeyId = keyMaterial.keyId,
            encryptedSizeBytes = validCiphertext.size.toLong(),
            encryptedChecksum = encRes.encryptedSha256,
            plaintextSizeBytes = plaintext.size.toLong()
        )

        val outcome = executeWithLease(transferId, "owner-1")
        assertTrue("Encrypted download should succeed: $outcome", outcome is TransferOutcome.Completed)
        assertTrue(destFile.exists())
        assertArrayEquals(plaintext, destFile.readBytes())

        // Now test corrupted ciphertext download
        val corruptedCiphertext = validCiphertext.copyOf()
        corruptedCiphertext[corruptedCiphertext.lastIndex] = (corruptedCiphertext.last().toInt() xor 0xFF).toByte()
        fakeApi.downloadFiles["enc_file_corrupt"] = corruptedCiphertext

        val corruptDestFile = File(tempDir, "should_not_exist.bin")
        val corruptTransferId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileId = "enc_file_corrupt",
            fileName = "should_not_exist.bin",
            localPath = corruptDestFile.absolutePath,
            direction = TransferDirection.DOWNLOAD,
            totalBytes = corruptedCiphertext.size.toLong(),
            isEncrypted = true,
            encryptionFormatVersion = CryptoConstants.FORMAT_VERSION_V1,
            encryptionKeyId = keyMaterial.keyId
        )

        val corruptOutcome = executeWithLease(corruptTransferId, "owner-1")
        assertTrue("Corrupted download must fail permanently: $corruptOutcome", corruptOutcome is TransferOutcome.Permanent)
        assertFalse("Corrupted plaintext must never be committed", corruptDestFile.exists())
        keyMaterial.destroy()
    }

    @Test
    fun `existing plaintext files continue working without encryption and are never auto-encrypted`() = runBlocking {
        val plainBytes = "Legacy plaintext file content".toByteArray()
        val plainSource = File(tempDir, "legacy_notes.txt").apply { writeBytes(plainBytes) }

        val uploadId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileName = "legacy_notes.txt",
            localPath = Uri.fromFile(plainSource).toString(),
            remotePath = "root",
            direction = TransferDirection.UPLOAD,
            totalBytes = plainBytes.size.toLong(),
            isEncrypted = false
        )

        val uploadOutcome = executeWithLease(uploadId, "owner-1")
        assertTrue(uploadOutcome is TransferOutcome.Completed)
        val sessionReq = fakeApi.lastUploadSessionRequest!!
        assertEquals("legacy_notes.txt", sessionReq.fileName)
        assertFalse(sessionReq.isEncrypted)
        assertArrayEquals(plainBytes, fakeApi.uploadedBytesBySession[fakeApi.lastSessionId]!!)

        // Download legacy plaintext file
        fakeApi.downloadFiles["plain_remote_1"] = plainBytes
        val plainDest = File(tempDir, "downloaded_legacy_notes.txt")
        val downloadId = transferRepo.enqueueTransfer(
            accountId = "acc_1",
            fileId = "plain_remote_1",
            fileName = "legacy_notes.txt",
            localPath = plainDest.absolutePath,
            direction = TransferDirection.DOWNLOAD,
            totalBytes = plainBytes.size.toLong(),
            isEncrypted = false
        )
        val dlOutcome = executeWithLease(downloadId, "owner-1")
        assertTrue(dlOutcome is TransferOutcome.Completed)
        assertArrayEquals(plainBytes, plainDest.readBytes())
    }

    @Test
    fun `ThumbnailStore never returns remote URL for encrypted files`() = runBlocking {
        val thumbStore = ThumbnailStore(
            context = context,
            dao = db.thumbnailDao(),
            contentKeyProvider = keyManager,
            streamFileCrypto = streamCrypto
        )
        val encryptedFile = CloudFile(
            id = "enc_img_1",
            name = "photo.jpg",
            type = "jpg",
            mimeType = "image/jpeg",
            thumbUrl = "https://server.example/thumb/enc_img_1",
            isEncrypted = true,
            encryptionFormatVersion = 1,
            encryptionKeyId = "k_123",
            opaqueRemoteName = "cyte_123456.bin",
            originalMetadataAvailable = true
        )

        val resolved = thumbStore.resolve(
            accountId = "acc_1",
            providerType = "cloudyte",
            file = encryptedFile,
            sizePx = 256,
            localSource = null
        )
        assertNull("ThumbnailStore must never return remote thumb URL for encrypted file", resolved)
    }

    private class FakeCloudyteApi : CloudyteApi {
        var lastUploadSessionRequest: UploadSessionRequest? = null
        var lastSessionId: String = ""
        val uploadedBytesBySession = mutableMapOf<String, ByteArray>()
        val downloadFiles = mutableMapOf<String, ByteArray>()
        private var sessionCounter = 0

        override suspend fun createUploadSession(
            accountId: String,
            request: UploadSessionRequest
        ): Response<UploadSessionResponse> {
            lastUploadSessionRequest = request
            val sid = "session_${++sessionCounter}"
            lastSessionId = sid
            uploadedBytesBySession[sid] = ByteArray(0)
            return Response.success(
                UploadSessionResponse(
                    sessionId = sid,
                    status = "ACTIVE",
                    receivedBytes = 0L,
                    totalBytes = request.size,
                    chunkSize = 64L * 1024L
                )
            )
        }

        override suspend fun getUploadSession(sessionId: String): Response<UploadSessionResponse> {
            val current = uploadedBytesBySession[sessionId] ?: ByteArray(0)
            val total = lastUploadSessionRequest?.size ?: current.size.toLong()
            return Response.success(
                UploadSessionResponse(
                    sessionId = sessionId,
                    status = "ACTIVE",
                    receivedBytes = current.size.toLong(),
                    totalBytes = total,
                    chunkSize = 64L * 1024L
                )
            )
        }

        override suspend fun uploadChunk(
            sessionId: String,
            contentRange: String,
            chunk: MultipartBody.Part
        ): Response<UploadChunkResponse> {
            val buffer = Buffer()
            chunk.body.writeTo(buffer)
            val chunkBytes = buffer.readByteArray()
            val startOffset = contentRange.removePrefix("bytes ").substringBefore("-").toIntOrNull() ?: 0
            val existing = uploadedBytesBySession[sessionId] ?: ByteArray(0)
            val newSize = maxOf(existing.size, startOffset + chunkBytes.size)
            val merged = existing.copyOf(newSize)
            System.arraycopy(chunkBytes, 0, merged, startOffset, chunkBytes.size)
            uploadedBytesBySession[sessionId] = merged
            val total = lastUploadSessionRequest?.size ?: merged.size.toLong()
            return Response.success(
                UploadChunkResponse(
                    sessionId = sessionId,
                    receivedBytes = merged.size.toLong(),
                    totalBytes = total,
                    nextOffset = merged.size.toLong(),
                    complete = merged.size.toLong() >= total
                )
            )
        }

        override suspend fun completeUploadSession(sessionId: String): Response<UploadCompleteResponse> {
            return Response.success(
                UploadCompleteResponse(
                    success = true,
                    sessionId = sessionId,
                    checksum = lastUploadSessionRequest?.checksum
                )
            )
        }

        override suspend fun cancelUploadSession(sessionId: String): Response<Unit> = Response.success(Unit)

        override suspend fun downloadFile(
            accountId: String,
            fileId: String,
            preview: Int?,
            range: String?
        ): Response<ResponseBody> {
            val full = downloadFiles[fileId]
                ?: return Response.error(404, "Not found".toResponseBody("text/plain".toMediaType()))
            val start = if (range != null && range.startsWith("bytes=")) {
                range.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
            } else 0
            val slice = if (start in 0..full.size) full.copyOfRange(start, full.size) else ByteArray(0)
            val code = if (start > 0) 206 else 200
            return Response.success(code, slice.toResponseBody("application/octet-stream".toMediaType()))
        }

        override suspend fun getPublicSettings(): PublicSettingsResponse = PublicSettingsResponse()
        override suspend fun register(request: RegisterRequest): Response<ResponseBody> = Response.success("".toResponseBody())
        override suspend fun login(request: LoginRequest): Response<LoginResponse> = Response.success(LoginResponse())
        override suspend fun logout(): Response<Unit> = Response.success(Unit)
        override suspend fun getProfile(): UserProfile = UserProfile(id = "u1", username = "tester")
        override suspend fun updateSettings(request: UserSettingsRequest): Response<Unit> = Response.success(Unit)
        override suspend fun putEncryptionKeyBackup(accountId: String, backup: com.cloudyte.app.api.EncryptionKeyBackup): Response<Unit> = Response.success(Unit)
        override suspend fun getEncryptionKeyBackup(accountId: String): Response<com.cloudyte.app.api.EncryptionKeyBackup> =
            Response.error(404, "".toResponseBody())
        override suspend fun deleteEncryptionKeyBackup(accountId: String): Response<Unit> = Response.success(Unit)
        override suspend fun getAccounts(): List<Account> = emptyList()
        override suspend fun deleteAccount(id: String): Response<Unit> = Response.success(Unit)
        override suspend fun getGoogleDriveLink(): OAuthLinkResponse = OAuthLinkResponse()
        override suspend fun getOneDriveLink(): OAuthLinkResponse = OAuthLinkResponse()
        override suspend fun registerTgDrive(request: TgDriveRegisterRequest): Response<Unit> = Response.success(Unit)
        override suspend fun getFiles(
            accountId: String?,
            folderId: String?,
            search: String?,
            cursor: String?,
            limit: Int?,
            ifNoneMatch: String?
        ): Response<List<CloudFile>> = Response.success(emptyList())
        override suspend fun createFolder(accountId: String?, request: FolderRequest): Response<Unit> = Response.success(Unit)
        override suspend fun uploadFile(
            accountId: String,
            folderIdQuery: String?,
            file: MultipartBody.Part,
            folderIdPart: RequestBody?,
            parentIdPart: RequestBody?,
            idempotencyKey: String?,
            checksum: String?,
            uploadSession: String?
        ): Response<ResponseBody> = Response.success("".toResponseBody())
        override suspend fun renameFile(fileId: String, accountId: String?, request: RenameRequest): Response<Unit> = Response.success(Unit)
        override suspend fun deleteFile(fileId: String, accountId: String?): Response<Unit> = Response.success(Unit)
        override suspend fun moveFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
        override suspend fun copyFile(request: MoveCopyRequest): Response<Unit> = Response.success(Unit)
        override suspend fun shareFile(request: ShareRequest): ShareResponse = ShareResponse(url = "https://example.com/s/1")
        override suspend fun getStorageSummary(accountId: String?): StorageSummary = StorageSummary(accountId = accountId.orEmpty(), accountType = "cloudyte", usedBytes = 0L)
    }
}

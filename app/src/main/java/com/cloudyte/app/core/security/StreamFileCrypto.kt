package com.cloudyte.app.core.security

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedFileHeader(
    val magic: String = CryptoConstants.MAGIC_STRING,
    val formatVersion: Int = CryptoConstants.FORMAT_VERSION_V1,
    val flags: Byte = 0,
    val fileSalt: ByteArray,
    val chunkSize: Int = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES,
    val plaintextSize: Long,
    val keyId: String,
    val reserved: ByteArray = ByteArray(CryptoConstants.HEADER_RESERVED_LENGTH_BYTES)
) {
    val serializedSizeBytes: Int
        get() = 4 + 1 + 1 + CryptoConstants.FILE_SALT_LENGTH_BYTES + 4 + 8 +
            2 + keyId.toByteArray(StandardCharsets.UTF_8).size +
            2 + reserved.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedFileHeader) return false
        return magic == other.magic &&
            formatVersion == other.formatVersion &&
            flags == other.flags &&
            fileSalt.contentEquals(other.fileSalt) &&
            chunkSize == other.chunkSize &&
            plaintextSize == other.plaintextSize &&
            keyId == other.keyId &&
            reserved.contentEquals(other.reserved)
    }

    override fun hashCode(): Int {
        var result = magic.hashCode()
        result = 31 * result + formatVersion
        result = 31 * result + flags
        result = 31 * result + fileSalt.contentHashCode()
        result = 31 * result + chunkSize
        result = 31 * result + plaintextSize.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + reserved.contentHashCode()
        return result
    }
}

data class EncryptedStreamResult(
    val header: EncryptedFileHeader,
    val plaintextSizeBytes: Long,
    val plaintextSha256: String,
    val encryptedSizeBytes: Long,
    val encryptedSha256: String,
    val frameCount: Int
)

data class EncryptedStagingResult(
    val stagingFile: File,
    val opaqueRemoteName: String,
    val header: EncryptedFileHeader,
    val plaintextSizeBytes: Long,
    val plaintextSha256: String,
    val encryptedSizeBytes: Long,
    val encryptedSha256: String,
    val frameCount: Int
)

/**
 * Streaming chunked file encryption and decryption implementing the `CYTE1` format.
 *
 * File format `CYTE1`:
 * Header:
 * - Magic: 4 bytes ASCII "CYTE"
 * - Format version: 1 byte (1)
 * - Flags: 1 byte (0)
 * - File salt: 8 bytes random
 * - Chunk size: 4 bytes big-endian Int (default 1,048,576 = 1 MiB)
 * - Original plaintext size: 8 bytes big-endian Long
 * - Key ID length: 2 bytes big-endian unsigned Short
 * - Key ID: UTF-8 bytes
 * - Reserved length: 2 bytes big-endian unsigned Short (16)
 * - Reserved: 16 zero bytes
 *
 * Frames (0..N):
 * - Frame index: 4 bytes big-endian Int (0, 1, 2, ...)
 * - Plaintext length: 4 bytes big-endian Int (0..chunkSize)
 * - Final-frame flag: 1 byte (1 = final frame, 0 = non-final frame)
 * - Ciphertext + 16-byte GCM authentication tag: (plaintextLength + 16) bytes
 *
 * Per-frame 12-byte GCM nonce:
 * - Bytes 0..7: `fileSalt` (8 bytes) with byte 0 XORed with `formatVersion`
 * - Bytes 8..11: big-endian `frameIndex` (4 bytes)
 *
 * Per-frame AAD:
 * - Binds the full header (`magic`, `formatVersion`, `flags`, `fileSalt`, `chunkSize`, `plaintextSize`, `keyId`, `reserved`)
 *   plus frame metadata (`frameIndex`, `plaintextLength`, `isFinalFrame`).
 *   Any tampering with header fields or frame metadata causes GCM authentication to fail immediately.
 */
class StreamFileCrypto(
    private val secureRandom: SecureRandom = SecureRandom()
) {

    /**
     * Encrypts a source [Uri] (`content://` or `file://`) into a dedicated staging file
     * `<cacheDir>/encrypted_staging/<transferId>.cyte`.
     * Writes to `<transferId>.cyte.tmp` first, validates the generated `CYTE1` file, and atomically
     * renames to `<transferId>.cyte`.
     */
    @Throws(CryptoException::class)
    fun encryptUriToStagingFile(
        context: Context,
        sourceUri: Uri,
        transferId: String,
        keyMaterial: ContentKeyMaterial,
        chunkSize: Int = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES,
        existingOpaqueRemoteName: String? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (plaintextBytesProcessed: Long) -> Unit = {}
    ): EncryptedStagingResult {
        val stagingDir = SecureFileCleanup.getEncryptedStagingDir(context)
        val tmpFile = File(stagingDir, "$transferId.cyte.tmp")
        val finalStagingFile = File(stagingDir, "$transferId.cyte")

        SecureFileCleanup.deleteFileSafely(tmpFile)

        // First determine exact plaintext size by querying or counting stream bytes
        val plaintextSize = resolvePlaintextSize(context, sourceUri)

        val openSourceStream: () -> InputStream = {
            when (sourceUri.scheme?.lowercase()) {
                "file" -> {
                    val path = sourceUri.path
                        ?: throw CryptoException("Invalid file:// URI path: $sourceUri")
                    FileInputStream(File(path))
                }
                else -> {
                    context.contentResolver.openInputStream(sourceUri)
                        ?: throw CryptoException("Unable to open InputStream for URI: $sourceUri")
                }
            }
        }

        try {
            val streamResult = openSourceStream().use { rawInput ->
                BufferedInputStream(rawInput, CryptoConstants.STREAM_BUFFER_SIZE_BYTES).use { bufferedIn ->
                    FileOutputStream(tmpFile).use { rawOut ->
                        BufferedOutputStream(rawOut, CryptoConstants.STREAM_BUFFER_SIZE_BYTES).use { bufferedOut ->
                            encryptStream(
                                input = bufferedIn,
                                output = bufferedOut,
                                keyMaterial = keyMaterial,
                                plaintextSize = plaintextSize,
                                chunkSize = chunkSize,
                                checkCancelled = checkCancelled,
                                onProgress = onProgress
                            )
                        }
                    }
                }
            }

            checkCancelled()

            // Verify staging file structure and size before committing
            val inspectedHeader = validateEncryptedFile(
                file = tmpFile,
                expectedKeyId = keyMaterial.keyId
            )
            if (tmpFile.length() != streamResult.encryptedSizeBytes) {
                throw CorruptedCiphertextException(
                    "Staging file size mismatch: expected ${streamResult.encryptedSizeBytes}, actual ${tmpFile.length()}"
                )
            }

            SecureFileCleanup.deleteFileSafely(finalStagingFile)
            if (!tmpFile.renameTo(finalStagingFile)) {
                tmpFile.copyTo(finalStagingFile, overwrite = true)
                SecureFileCleanup.deleteFileSafely(tmpFile)
            }

            val opaqueName = existingOpaqueRemoteName?.takeIf {
                it.startsWith(CryptoConstants.OPAQUE_FILE_PREFIX) && it.endsWith(CryptoConstants.OPAQUE_FILE_SUFFIX)
            } ?: generateOpaqueRemoteFileName()

            return EncryptedStagingResult(
                stagingFile = finalStagingFile,
                opaqueRemoteName = opaqueName,
                header = inspectedHeader,
                plaintextSizeBytes = streamResult.plaintextSizeBytes,
                plaintextSha256 = streamResult.plaintextSha256,
                encryptedSizeBytes = streamResult.encryptedSizeBytes,
                encryptedSha256 = streamResult.encryptedSha256,
                frameCount = streamResult.frameCount
            )
        } catch (t: Throwable) {
            SecureFileCleanup.deleteFileSafely(tmpFile)
            throw t
        }
    }

    /**
     * Encrypts [input] to [output] in `CYTE1` streaming format using fixed-size chunk buffers.
     */
    @Throws(CryptoException::class)
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        keyMaterial: ContentKeyMaterial,
        plaintextSize: Long,
        chunkSize: Int = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES,
        fileSalt: ByteArray? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (plaintextBytesProcessed: Long) -> Unit = {}
    ): EncryptedStreamResult {
        if (plaintextSize < 0L) {
            throw InvalidEncryptedHeaderException("plaintextSize must be >= 0 (was $plaintextSize)")
        }
        if (chunkSize < CryptoConstants.MIN_CHUNK_SIZE_BYTES || chunkSize > CryptoConstants.MAX_CHUNK_SIZE_BYTES) {
            throw InvalidEncryptedHeaderException("chunkSize ($chunkSize) is outside allowed range")
        }

        val salt = fileSalt?.copyOf() ?: ByteArray(CryptoConstants.FILE_SALT_LENGTH_BYTES).also {
            secureRandom.nextBytes(it)
        }
        if (salt.size != CryptoConstants.FILE_SALT_LENGTH_BYTES) {
            throw InvalidEncryptedHeaderException("fileSalt must be ${CryptoConstants.FILE_SALT_LENGTH_BYTES} bytes")
        }

        val header = EncryptedFileHeader(
            magic = CryptoConstants.MAGIC_STRING,
            formatVersion = CryptoConstants.FORMAT_VERSION_V1,
            flags = 0,
            fileSalt = salt,
            chunkSize = chunkSize,
            plaintextSize = plaintextSize,
            keyId = keyMaterial.keyId
        )

        val rawKey = keyMaterial.copyKeyBytes()
        val plaintextDigest = MessageDigest.getInstance("SHA-256")
        val encryptedDigest = MessageDigest.getInstance("SHA-256")
        val countingEncryptedOut = CountingDigestOutputStream(output, encryptedDigest)
        val dataOut = DataOutputStream(countingEncryptedOut)

        try {
            writeHeader(dataOut, header)

            val chunkBuffer = ByteArray(chunkSize)
            var frameIndex = 0
            var totalPlaintextRead = 0L

            if (plaintextSize == 0L) {
                checkCancelled()
                val extraByte = input.read()
                if (extraByte != -1) {
                    throw InvalidEncryptedHeaderException("Input stream contains bytes but plaintextSize was declared 0")
                }
                val encryptedFrame = encryptFrame(
                    frameIndex = 0,
                    plaintextChunk = ByteArray(0),
                    plaintextLength = 0,
                    isFinalFrame = true,
                    header = header,
                    keyBytes = rawKey
                )
                writeFrame(dataOut, frameIndex = 0, plaintextLength = 0, isFinalFrame = true, ciphertextWithTag = encryptedFrame)
                frameIndex = 1
                onProgress(0L)
            } else {
                var pendingByte: Int = input.read()
                if (pendingByte == -1) {
                    throw InvalidEncryptedHeaderException("Input stream was empty but expected $plaintextSize bytes")
                }

                while (pendingByte != -1) {
                    checkCancelled()
                    chunkBuffer[0] = pendingByte.toByte()
                    var bytesInChunk = 1

                    while (bytesInChunk < chunkSize) {
                        val readCount = input.read(
                            chunkBuffer,
                            bytesInChunk,
                            chunkSize - bytesInChunk
                        )
                        if (readCount == -1) {
                            break
                        }
                        if (readCount > 0) {
                            bytesInChunk += readCount
                        }
                    }

                    plaintextDigest.update(chunkBuffer, 0, bytesInChunk)
                    totalPlaintextRead += bytesInChunk

                    pendingByte = input.read()
                    val isFinalFrame = (pendingByte == -1)

                    if (!isFinalFrame && bytesInChunk != chunkSize) {
                        throw InvalidEncryptedHeaderException(
                            "Non-final frame $frameIndex has partial length $bytesInChunk (expected $chunkSize)"
                        )
                    }

                    val encryptedFrame = encryptFrame(
                        frameIndex = frameIndex,
                        plaintextChunk = chunkBuffer,
                        plaintextLength = bytesInChunk,
                        isFinalFrame = isFinalFrame,
                        header = header,
                        keyBytes = rawKey
                    )
                    writeFrame(
                        dataOut = dataOut,
                        frameIndex = frameIndex,
                        plaintextLength = bytesInChunk,
                        isFinalFrame = isFinalFrame,
                        ciphertextWithTag = encryptedFrame
                    )
                    frameIndex++
                    onProgress(totalPlaintextRead)
                }

                if (totalPlaintextRead != plaintextSize) {
                    throw InvalidEncryptedHeaderException(
                        "Plaintext size mismatch during encryption: declared=$plaintextSize, actual=$totalPlaintextRead"
                    )
                }
            }

            dataOut.flush()
            return EncryptedStreamResult(
                header = header,
                plaintextSizeBytes = totalPlaintextRead,
                plaintextSha256 = bytesToHex(plaintextDigest.digest()),
                encryptedSizeBytes = countingEncryptedOut.bytesWritten,
                encryptedSha256 = bytesToHex(encryptedDigest.digest()),
                frameCount = frameIndex
            )
        } finally {
            rawKey.fill(0)
        }
    }

    /**
     * Decrypts a `CYTE1` encrypted stream [input] to [output], verifying the header and every
     * frame's AES-GCM authentication tag, frame ordering, final-frame marker, and exact plaintext size.
     */
    @Throws(CryptoException::class)
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        keyMaterial: ContentKeyMaterial,
        checkCancelled: () -> Unit = {},
        onProgress: (plaintextBytesWritten: Long) -> Unit = {}
    ): EncryptedFileHeader {
        val dataIn = DataInputStream(input)
        val header = readAndValidateHeader(dataIn)

        if (header.keyId != keyMaterial.keyId) {
            throw WrongKeyIdException(expectedKeyId = header.keyId, actualKeyId = keyMaterial.keyId)
        }

        val rawKey = keyMaterial.copyKeyBytes()
        try {
            var expectedFrameIndex = 0
            var totalPlaintextWritten = 0L
            var sawFinalFrame = false

            while (!sawFinalFrame) {
                checkCancelled()
                val frameIndex = try {
                    dataIn.readInt()
                } catch (e: EOFException) {
                    throw CorruptedCiphertextException(
                        "Truncated encrypted stream: missing final frame at index $expectedFrameIndex",
                        e
                    )
                }

                if (frameIndex != expectedFrameIndex) {
                    throw FrameAuthenticationException(
                        expectedFrameIndex,
                        "Out-of-order or duplicate frame index: expected $expectedFrameIndex, found $frameIndex"
                    )
                }

                val plaintextLength = try {
                    dataIn.readInt()
                } catch (e: EOFException) {
                    throw CorruptedCiphertextException("Truncated frame header at frame $frameIndex", e)
                }

                val finalFlagByte = try {
                    dataIn.readByte().toInt()
                } catch (e: EOFException) {
                    throw CorruptedCiphertextException("Truncated frame final flag at frame $frameIndex", e)
                }

                val isFinalFrame = when (finalFlagByte) {
                    0 -> false
                    1 -> true
                    else -> throw CorruptedCiphertextException(
                        "Invalid final-frame flag ($finalFlagByte) at frame $frameIndex"
                    )
                }

                validateFrameLengths(
                    frameIndex = frameIndex,
                    plaintextLength = plaintextLength,
                    isFinalFrame = isFinalFrame,
                    header = header
                )

                val ciphertextLength = plaintextLength + CryptoConstants.GCM_TAG_LENGTH_BYTES
                val ciphertextWithTag = ByteArray(ciphertextLength)
                try {
                    dataIn.readFully(ciphertextWithTag)
                } catch (e: EOFException) {
                    throw CorruptedCiphertextException(
                        "Truncated frame ciphertext at frame $frameIndex (expected $ciphertextLength bytes)",
                        e
                    )
                }

                val decryptedChunk = decryptFrame(
                    frameIndex = frameIndex,
                    plaintextLength = plaintextLength,
                    isFinalFrame = isFinalFrame,
                    ciphertextWithTag = ciphertextWithTag,
                    header = header,
                    keyBytes = rawKey
                )

                if (decryptedChunk.size != plaintextLength) {
                    throw CorruptedCiphertextException(
                        "Decrypted frame $frameIndex length mismatch: expected $plaintextLength, got ${decryptedChunk.size}"
                    )
                }

                if (decryptedChunk.isNotEmpty()) {
                    output.write(decryptedChunk)
                }

                totalPlaintextWritten += decryptedChunk.size
                expectedFrameIndex++
                sawFinalFrame = isFinalFrame
                onProgress(totalPlaintextWritten)
            }

            // Ensure there are no unexpected trailing bytes after the final frame
            val trailingByte = dataIn.read()
            if (trailingByte != -1) {
                throw CorruptedCiphertextException("Unexpected trailing bytes after final frame")
            }

            if (totalPlaintextWritten != header.plaintextSize) {
                throw CorruptedCiphertextException(
                    "Total decrypted size ($totalPlaintextWritten) does not match header plaintextSize (${header.plaintextSize})"
                )
            }

            output.flush()
            return header
        } finally {
            rawKey.fill(0)
        }
    }

    /**
     * Decrypts [encryptedFile] into [destinationTempFile].
     * If any header validation or frame GCM authentication fails, [destinationTempFile] is deleted
     * immediately so unauthenticated partial plaintext never remains on disk.
     */
    @Throws(CryptoException::class)
    fun decryptEncryptedFileToPlaintext(
        encryptedFile: File,
        destinationTempFile: File,
        keyMaterial: ContentKeyMaterial,
        checkCancelled: () -> Unit = {},
        onProgress: (plaintextBytesWritten: Long) -> Unit = {}
    ): EncryptedFileHeader {
        if (!encryptedFile.exists()) {
            throw CorruptedCiphertextException("Encrypted source file does not exist: ${encryptedFile.name}")
        }
        destinationTempFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        SecureFileCleanup.deleteFileSafely(destinationTempFile)

        return try {
            FileInputStream(encryptedFile).use { rawIn ->
                BufferedInputStream(rawIn, CryptoConstants.STREAM_BUFFER_SIZE_BYTES).use { bufferedIn ->
                    FileOutputStream(destinationTempFile).use { rawOut ->
                        BufferedOutputStream(rawOut, CryptoConstants.STREAM_BUFFER_SIZE_BYTES).use { bufferedOut ->
                            decryptStream(
                                input = bufferedIn,
                                output = bufferedOut,
                                keyMaterial = keyMaterial,
                                checkCancelled = checkCancelled,
                                onProgress = onProgress
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            SecureFileCleanup.deleteFileSafely(destinationTempFile)
            throw t
        }
    }

    /**
     * Encrypts a single frame with AES-256-GCM using a unique per-frame 12-byte nonce and
     * per-frame AAD bound to the file header and frame parameters.
     */
    @Throws(CryptoException::class)
    fun encryptFrame(
        frameIndex: Int,
        plaintextChunk: ByteArray,
        plaintextLength: Int = plaintextChunk.size,
        isFinalFrame: Boolean,
        header: EncryptedFileHeader,
        keyBytes: ByteArray
    ): ByteArray {
        validateFrameLengths(
            frameIndex = frameIndex,
            plaintextLength = plaintextLength,
            isFinalFrame = isFinalFrame,
            header = header
        )
        val nonce = deriveFrameNonce(header.fileSalt, header.formatVersion, frameIndex)
        val aad = buildFrameAad(header, frameIndex, plaintextLength, isFinalFrame)
        return try {
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(aad)
            cipher.doFinal(plaintextChunk, 0, plaintextLength)
        } catch (e: Exception) {
            throw CryptoException("Failed to encrypt frame $frameIndex", e)
        }
    }

    /**
     * Decrypts and authenticates a single frame with AES-256-GCM.
     */
    @Throws(CryptoException::class)
    fun decryptFrame(
        frameIndex: Int,
        plaintextLength: Int,
        isFinalFrame: Boolean,
        ciphertextWithTag: ByteArray,
        header: EncryptedFileHeader,
        keyBytes: ByteArray
    ): ByteArray {
        validateFrameLengths(
            frameIndex = frameIndex,
            plaintextLength = plaintextLength,
            isFinalFrame = isFinalFrame,
            header = header
        )
        val expectedCiphertextSize = plaintextLength + CryptoConstants.GCM_TAG_LENGTH_BYTES
        if (ciphertextWithTag.size != expectedCiphertextSize) {
            throw CorruptedCiphertextException(
                "Invalid ciphertext+tag size at frame $frameIndex: expected $expectedCiphertextSize, got ${ciphertextWithTag.size}"
            )
        }

        val nonce = deriveFrameNonce(header.fileSalt, header.formatVersion, frameIndex)
        val aad = buildFrameAad(header, frameIndex, plaintextLength, isFinalFrame)
        return try {
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: AEADBadTagException) {
            throw FrameAuthenticationException(
                frameIndex = frameIndex,
                message = "GCM authentication tag verification failed at frame $frameIndex",
                cause = e
            )
        } catch (e: Exception) {
            throw FrameAuthenticationException(
                frameIndex = frameIndex,
                message = "Failed to authenticate or decrypt frame $frameIndex",
                cause = e
            )
        }
    }

    /**
     * Inspects and validates the `CYTE1` header from [file] without reading all frames.
     */
    @Throws(CryptoException::class)
    fun inspectEncryptedHeader(file: File): EncryptedFileHeader {
        if (!file.exists() || !file.isFile) {
            throw InvalidEncryptedHeaderException("Encrypted file does not exist: ${file.name}")
        }
        FileInputStream(file).use { fis ->
            return inspectEncryptedHeader(fis)
        }
    }

    /**
     * Inspects and validates the `CYTE1` header from [input].
     */
    @Throws(CryptoException::class)
    fun inspectEncryptedHeader(input: InputStream): EncryptedFileHeader {
        return readAndValidateHeader(DataInputStream(input))
    }

    /**
     * Checks whether [file] starts with the 4-byte `"CYTE"` magic header.
     */
    fun hasCyteMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                val read = fis.read(magic)
                read == 4 && magic.contentEquals(CryptoConstants.MAGIC_BYTES)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates the entire structural framing of an encrypted `CYTE1` file on disk
     * (header, frame indices, frame lengths, final-frame marker, and exact total byte length).
     */
    @Throws(CryptoException::class)
    fun validateEncryptedFile(file: File, expectedKeyId: String? = null): EncryptedFileHeader {
        if (!file.exists() || !file.isFile) {
            throw InvalidEncryptedHeaderException("Encrypted file does not exist: ${file.name}")
        }
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis, CryptoConstants.STREAM_BUFFER_SIZE_BYTES).use { buffered ->
                val dataIn = DataInputStream(buffered)
                val header = readAndValidateHeader(dataIn)
                if (!expectedKeyId.isNullOrBlank() && header.keyId != expectedKeyId) {
                    throw WrongKeyIdException(expectedKeyId = expectedKeyId, actualKeyId = header.keyId)
                }

                var expectedFrameIndex = 0
                var totalPlaintextCounted = 0L
                var sawFinal = false

                while (!sawFinal) {
                    val frameIndex = try {
                        dataIn.readInt()
                    } catch (e: EOFException) {
                        throw CorruptedCiphertextException("Truncated encrypted file before final frame", e)
                    }
                    if (frameIndex != expectedFrameIndex) {
                        throw FrameAuthenticationException(
                            expectedFrameIndex,
                            "Invalid frame index $frameIndex (expected $expectedFrameIndex)"
                        )
                    }
                    val plaintextLength = try {
                        dataIn.readInt()
                    } catch (e: EOFException) {
                        throw CorruptedCiphertextException("Truncated frame length at frame $frameIndex", e)
                    }
                    val finalFlagByte = try {
                        dataIn.readByte().toInt()
                    } catch (e: EOFException) {
                        throw CorruptedCiphertextException("Truncated final flag at frame $frameIndex", e)
                    }
                    val isFinal = when (finalFlagByte) {
                        0 -> false
                        1 -> true
                        else -> throw CorruptedCiphertextException("Invalid final flag $finalFlagByte at frame $frameIndex")
                    }

                    validateFrameLengths(frameIndex, plaintextLength, isFinal, header)

                    val ciphertextAndTagBytes = (plaintextLength + CryptoConstants.GCM_TAG_LENGTH_BYTES).toLong()
                    skipFully(dataIn, ciphertextAndTagBytes, frameIndex)

                    totalPlaintextCounted += plaintextLength
                    expectedFrameIndex++
                    sawFinal = isFinal
                }

                if (dataIn.read() != -1) {
                    throw CorruptedCiphertextException("Encrypted file has trailing bytes after final frame")
                }

                if (totalPlaintextCounted != header.plaintextSize) {
                    throw CorruptedCiphertextException(
                        "Encrypted file frame plaintext sum ($totalPlaintextCounted) != header plaintextSize (${header.plaintextSize})"
                    )
                }

                return header
            }
        }
    }

    /**
     * Computes the lowercase hex SHA-256 checksum of a file on disk.
     */
    fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            DigestInputStream(BufferedInputStream(fis, CryptoConstants.STREAM_BUFFER_SIZE_BYTES), digest).use { dis ->
                val buffer = ByteArray(CryptoConstants.STREAM_BUFFER_SIZE_BYTES)
                while (dis.read(buffer) != -1) {
                    // DigestInputStream updates digest
                }
            }
        }
        return bytesToHex(digest.digest())
    }

    fun generateOpaqueRemoteFileName(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "")
        return "${CryptoConstants.OPAQUE_FILE_PREFIX}${uuid}${CryptoConstants.OPAQUE_FILE_SUFFIX}"
    }

    private fun resolvePlaintextSize(context: Context, uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = File(uri.path ?: throw CryptoException("Missing file path in URI: $uri"))
            if (!file.exists()) throw CryptoException("Source file does not exist: ${file.name}")
            return file.length()
        }

        // Count actual readable bytes from ContentResolver stream so that encryption header
        // plaintextSize is guaranteed to match the exact stream byte count.
        val input = context.contentResolver.openInputStream(uri)
            ?: throw CryptoException("Unable to open InputStream for URI: $uri")
        return input.use { stream ->
            val buf = ByteArray(CryptoConstants.STREAM_BUFFER_SIZE_BYTES)
            var total = 0L
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                total += read
            }
            total
        }
    }

    private fun writeHeader(dataOut: DataOutputStream, header: EncryptedFileHeader) {
        val magicBytes = header.magic.toByteArray(StandardCharsets.US_ASCII)
        if (magicBytes.size != 4 || !magicBytes.contentEquals(CryptoConstants.MAGIC_BYTES)) {
            throw InvalidEncryptedHeaderException("Invalid magic in header: ${header.magic}")
        }
        val keyIdBytes = header.keyId.toByteArray(StandardCharsets.UTF_8)
        if (keyIdBytes.isEmpty() || keyIdBytes.size > CryptoConstants.MAX_KEY_ID_LENGTH_BYTES) {
            throw InvalidEncryptedHeaderException("Invalid keyId length: ${keyIdBytes.size}")
        }
        if (header.fileSalt.size != CryptoConstants.FILE_SALT_LENGTH_BYTES) {
            throw InvalidEncryptedHeaderException("Invalid fileSalt length: ${header.fileSalt.size}")
        }

        dataOut.write(magicBytes)
        dataOut.writeByte(header.formatVersion)
        dataOut.writeByte(header.flags.toInt())
        dataOut.write(header.fileSalt)
        dataOut.writeInt(header.chunkSize)
        dataOut.writeLong(header.plaintextSize)
        dataOut.writeShort(keyIdBytes.size)
        dataOut.write(keyIdBytes)
        dataOut.writeShort(header.reserved.size)
        dataOut.write(header.reserved)
    }

    private fun writeFrame(
        dataOut: DataOutputStream,
        frameIndex: Int,
        plaintextLength: Int,
        isFinalFrame: Boolean,
        ciphertextWithTag: ByteArray
    ) {
        dataOut.writeInt(frameIndex)
        dataOut.writeInt(plaintextLength)
        dataOut.writeByte(if (isFinalFrame) 1 else 0)
        dataOut.write(ciphertextWithTag)
    }

    private fun readAndValidateHeader(dataIn: DataInputStream): EncryptedFileHeader {
        val magic = ByteArray(4)
        try {
            dataIn.readFully(magic)
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing magic bytes)", e)
        }
        if (!magic.contentEquals(CryptoConstants.MAGIC_BYTES)) {
            throw InvalidEncryptedHeaderException("Invalid encrypted file magic header")
        }

        val formatVersion = try {
            dataIn.readUnsignedByte()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing version)", e)
        }
        if (formatVersion != CryptoConstants.FORMAT_VERSION_V1) {
            throw UnsupportedEncryptionVersionException(formatVersion)
        }

        val flags = try {
            dataIn.readByte()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing flags)", e)
        }
        if (flags.toInt() != 0) {
            throw InvalidEncryptedHeaderException("Unsupported encrypted header flags: $flags")
        }

        val fileSalt = ByteArray(CryptoConstants.FILE_SALT_LENGTH_BYTES)
        try {
            dataIn.readFully(fileSalt)
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing fileSalt)", e)
        }

        val chunkSize = try {
            dataIn.readInt()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing chunkSize)", e)
        }
        if (chunkSize < CryptoConstants.MIN_CHUNK_SIZE_BYTES || chunkSize > CryptoConstants.MAX_CHUNK_SIZE_BYTES) {
            throw InvalidEncryptedHeaderException("Invalid chunkSize in encrypted header: $chunkSize")
        }

        val plaintextSize = try {
            dataIn.readLong()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing plaintextSize)", e)
        }
        if (plaintextSize < 0L) {
            throw InvalidEncryptedHeaderException("Invalid negative plaintextSize in encrypted header: $plaintextSize")
        }

        val keyIdLength = try {
            dataIn.readUnsignedShort()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing keyIdLength)", e)
        }
        if (keyIdLength <= 0 || keyIdLength > CryptoConstants.MAX_KEY_ID_LENGTH_BYTES) {
            throw InvalidEncryptedHeaderException("Invalid keyIdLength in encrypted header: $keyIdLength")
        }

        val keyIdBytes = ByteArray(keyIdLength)
        try {
            dataIn.readFully(keyIdBytes)
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing keyId)", e)
        }
        val keyId = String(keyIdBytes, StandardCharsets.UTF_8)
        if (keyId.isBlank()) {
            throw InvalidEncryptedHeaderException("Blank keyId in encrypted header")
        }

        val reservedLength = try {
            dataIn.readUnsignedShort()
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing reservedLength)", e)
        }
        if (reservedLength != CryptoConstants.HEADER_RESERVED_LENGTH_BYTES) {
            throw InvalidEncryptedHeaderException("Invalid reservedLength in encrypted header: $reservedLength")
        }

        val reserved = ByteArray(reservedLength)
        try {
            dataIn.readFully(reserved)
        } catch (e: EOFException) {
            throw InvalidEncryptedHeaderException("Truncated encrypted header (missing reserved bytes)", e)
        }
        if (reserved.any { it.toInt() != 0 }) {
            throw InvalidEncryptedHeaderException("Non-zero reserved bytes in encrypted header")
        }

        return EncryptedFileHeader(
            magic = CryptoConstants.MAGIC_STRING,
            formatVersion = formatVersion,
            flags = flags,
            fileSalt = fileSalt,
            chunkSize = chunkSize,
            plaintextSize = plaintextSize,
            keyId = keyId,
            reserved = reserved
        )
    }

    private fun validateFrameLengths(
        frameIndex: Int,
        plaintextLength: Int,
        isFinalFrame: Boolean,
        header: EncryptedFileHeader
    ) {
        if (frameIndex < 0) {
            throw CorruptedCiphertextException("Negative frame index: $frameIndex")
        }
        if (plaintextLength < 0 || plaintextLength > header.chunkSize) {
            throw CorruptedCiphertextException(
                "Invalid frame plaintextLength ($plaintextLength) at frame $frameIndex (chunkSize=${header.chunkSize})"
            )
        }
        if (!isFinalFrame && plaintextLength != header.chunkSize) {
            throw CorruptedCiphertextException(
                "Non-final frame $frameIndex has length $plaintextLength != chunkSize ${header.chunkSize}"
            )
        }
        if (plaintextLength == 0 && (!isFinalFrame || frameIndex != 0 || header.plaintextSize != 0L)) {
            throw CorruptedCiphertextException(
                "Zero-length frame is only valid as final frame 0 of an empty file (frame=$frameIndex, plaintextSize=${header.plaintextSize})"
            )
        }
    }

    private fun skipFully(dataIn: DataInputStream, bytesToSkip: Long, frameIndex: Int) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(minOf(remaining, 8192L).toInt())
        while (remaining > 0L) {
            val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
            val read = dataIn.read(skipBuf, 0, toRead)
            if (read == -1) {
                throw CorruptedCiphertextException(
                    "Truncated frame payload at frame $frameIndex (missing $remaining bytes)"
                )
            }
            remaining -= read
        }
    }

    private class CountingDigestOutputStream(
        out: OutputStream,
        digest: MessageDigest
    ) : DigestOutputStream(out, digest) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            super.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }

    companion object {
        /**
         * Deterministically derives a unique 12-byte GCM nonce per frame from
         * the 8-byte random `fileSalt`, `formatVersion`, and 4-byte `frameIndex`.
         */
        internal fun deriveFrameNonce(
            fileSalt: ByteArray,
            formatVersion: Int,
            frameIndex: Int
        ): ByteArray {
            require(fileSalt.size == CryptoConstants.FILE_SALT_LENGTH_BYTES) {
                "fileSalt must be ${CryptoConstants.FILE_SALT_LENGTH_BYTES} bytes"
            }
            val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH_BYTES)
            System.arraycopy(fileSalt, 0, nonce, 0, CryptoConstants.FILE_SALT_LENGTH_BYTES)
            nonce[0] = (nonce[0].toInt() xor (formatVersion and 0xFF)).toByte()
            nonce[8] = ((frameIndex ushr 24) and 0xFF).toByte()
            nonce[9] = ((frameIndex ushr 16) and 0xFF).toByte()
            nonce[10] = ((frameIndex ushr 8) and 0xFF).toByte()
            nonce[11] = (frameIndex and 0xFF).toByte()
            return nonce
        }

        /**
         * Builds the per-frame Additional Authenticated Data (AAD) binding both the file header
         * and the frame metadata so neither header nor frames can be modified or reordered.
         */
        internal fun buildFrameAad(
            header: EncryptedFileHeader,
            frameIndex: Int,
            plaintextLength: Int,
            isFinalFrame: Boolean
        ): ByteArray {
            val keyIdBytes = header.keyId.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.allocate(
                4 + 1 + 1 + CryptoConstants.FILE_SALT_LENGTH_BYTES + 4 + 8 +
                    2 + keyIdBytes.size + 2 + header.reserved.size +
                    4 + 4 + 1
            )
            buffer.put(CryptoConstants.MAGIC_BYTES)
            buffer.put(header.formatVersion.toByte())
            buffer.put(header.flags)
            buffer.put(header.fileSalt)
            buffer.putInt(header.chunkSize)
            buffer.putLong(header.plaintextSize)
            buffer.putShort(keyIdBytes.size.toShort())
            buffer.put(keyIdBytes)
            buffer.putShort(header.reserved.size.toShort())
            buffer.put(header.reserved)
            buffer.putInt(frameIndex)
            buffer.putInt(plaintextLength)
            buffer.put(if (isFinalFrame) 1.toByte() else 0.toByte())
            return buffer.array()
        }

        internal fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }
    }
}

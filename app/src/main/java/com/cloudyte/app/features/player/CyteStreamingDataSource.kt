package com.cloudyte.app.features.player

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.core.security.ContentKeyManager
import com.cloudyte.app.core.security.CryptoConstants
import com.cloudyte.app.core.security.EncryptedFileHeader
import com.cloudyte.app.core.security.StreamFileCrypto
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

/**
 * Seekable Media3 [DataSource] that streams and decrypts `CYTE1` encrypted media on the fly
 * using HTTP `Range` requests per 1 MiB AEAD frame without downloading the entire file first.
 */
@OptIn(UnstableApi::class)
class CyteStreamingDataSource(
    private val sessionState: SessionState
) : BaseDataSource(/* isNetwork = */ true) {

    class SessionState(
        context: Context,
        val accountId: String,
        val file: CloudFile
    ) {
        private val appContext = context.applicationContext
        private val streamCrypto = StreamFileCrypto()
        val remoteUrl: String = ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)

        @Volatile
        private var cachedHeader: EncryptedFileHeader? = null

        @Volatile
        private var cachedKeyBytes: ByteArray? = null

        // Cache up to 4 decrypted frames (4 MiB) across seeks/reads for smooth scrubbing
        private val frameCache = object : LruCache<Int, ByteArray>(4) {}

        @Synchronized
        @Throws(IOException::class)
        fun ensureHeaderAndKey(): Pair<EncryptedFileHeader, ByteArray> {
            val existingHeader = cachedHeader
            val existingKey = cachedKeyBytes
            if (existingHeader != null && existingKey != null) {
                return existingHeader to existingKey
            }

            val req = Request.Builder()
                .url(remoteUrl)
                .header("Range", "bytes=0-255")
                .get()
                .build()

            val headerBytes = ApiClient.okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("Failed to fetch CYTE1 header (HTTP ${response.code})")
                }
                response.body?.bytes() ?: throw IOException("Empty CYTE1 header response")
            }

            val header = try {
                streamCrypto.inspectEncryptedHeader(ByteArrayInputStream(headerBytes))
            } catch (e: Exception) {
                throw IOException("Invalid CYTE1 encrypted header: ${e.message}", e)
            }

            val keyMaterial = try {
                ContentKeyManager(appContext).getContentKeyForDecryption(header.keyId)
            } catch (e: Exception) {
                throw IOException("Encryption key unavailable for streaming: ${e.message}", e)
            }
            val keyBytes = keyMaterial.copyKeyBytes()
            keyMaterial.destroy()

            cachedHeader = header
            cachedKeyBytes = keyBytes
            return header to keyBytes
        }

        @Throws(IOException::class)
        fun getDecryptedFrame(frameIndex: Int): ByteArray {
            synchronized(frameCache) {
                frameCache.get(frameIndex)?.let { return it }
            }

            val (header, keyBytes) = ensureHeaderAndKey()
            val chunkSize = header.chunkSize
            val plaintextSize = header.plaintextSize
            val lastFrameIndex = if (plaintextSize == 0L) {
                0
            } else {
                ((plaintextSize - 1L) / chunkSize.toLong()).toInt()
            }
            if (frameIndex < 0 || frameIndex > lastFrameIndex) {
                throw IOException("Requested CYTE1 frame $frameIndex out of bounds (0..$lastFrameIndex)")
            }

            val expectedPlaintextLen = when {
                plaintextSize == 0L -> 0
                frameIndex < lastFrameIndex -> chunkSize
                else -> (((plaintextSize - 1L) % chunkSize.toLong()) + 1L).toInt()
            }
            val nonFinalFrameWireSize = 9L + chunkSize.toLong() + CryptoConstants.GCM_TAG_LENGTH_BYTES.toLong()
            val wireStart = header.serializedSizeBytes.toLong() + frameIndex.toLong() * nonFinalFrameWireSize
            val expectedWireLen = 9 + expectedPlaintextLen + CryptoConstants.GCM_TAG_LENGTH_BYTES
            val wireEnd = wireStart + expectedWireLen.toLong() - 1L

            val req = Request.Builder()
                .url(remoteUrl)
                .header("Range", "bytes=$wireStart-$wireEnd")
                .get()
                .build()

            val frameWireBytes = ApiClient.okHttpClient.newCall(req).execute().use { response ->
                if (response.code != 206 && !response.isSuccessful) {
                    throw IOException("HTTP ${response.code} fetching encrypted frame $frameIndex")
                }
                val body = response.body ?: throw IOException("Empty body for encrypted frame $frameIndex")
                if (response.code == 200) {
                    // Server ignored Range header; skip to wireStart and read expectedWireLen
                    val stream = body.byteStream()
                    var toSkip = wireStart
                    val skipBuf = ByteArray(16 * 1024)
                    while (toSkip > 0L) {
                        val n = stream.read(skipBuf, 0, min(toSkip, skipBuf.size.toLong()).toInt())
                        if (n == -1) throw EOFException("Unexpected EOF skipping to frame $frameIndex")
                        toSkip -= n
                    }
                    val buf = ByteArray(expectedWireLen)
                    DataInputStream(stream).readFully(buf)
                    buf
                } else {
                    body.bytes()
                }
            }

            if (frameWireBytes.size < expectedWireLen) {
                throw EOFException("Truncated CYTE1 frame $frameIndex: expected $expectedWireLen bytes, got ${frameWireBytes.size}")
            }

            val dataIn = DataInputStream(ByteArrayInputStream(frameWireBytes))
            val wireFrameIndex = dataIn.readInt()
            val wirePlaintextLen = dataIn.readInt()
            val finalFlag = dataIn.readByte().toInt()
            val isFinalFrame = when (finalFlag) {
                0 -> false
                1 -> true
                else -> throw IOException("Invalid CYTE1 final-frame flag $finalFlag at frame $frameIndex")
            }
            if (wireFrameIndex != frameIndex) {
                throw IOException("CYTE1 frame index mismatch: expected $frameIndex, got $wireFrameIndex")
            }
            if (wirePlaintextLen != expectedPlaintextLen) {
                throw IOException("CYTE1 frame length mismatch at frame $frameIndex: expected $expectedPlaintextLen, got $wirePlaintextLen")
            }

            val ciphertextWithTag = ByteArray(wirePlaintextLen + CryptoConstants.GCM_TAG_LENGTH_BYTES)
            dataIn.readFully(ciphertextWithTag)

            val decrypted = try {
                streamCrypto.decryptFrame(
                    frameIndex = frameIndex,
                    plaintextLength = wirePlaintextLen,
                    isFinalFrame = isFinalFrame,
                    ciphertextWithTag = ciphertextWithTag,
                    header = header,
                    keyBytes = keyBytes
                )
            } catch (e: Exception) {
                throw IOException("CYTE1 frame $frameIndex authentication failed: ${e.message}", e)
            }

            synchronized(frameCache) {
                frameCache.put(frameIndex, decrypted)
            }
            return decrypted
        }
    }

    class Factory(
        context: Context,
        accountId: String,
        file: CloudFile
    ) : DataSource.Factory {
        private val sessionState = SessionState(context, accountId, file)

        override fun createDataSource(): DataSource {
            return CyteStreamingDataSource(sessionState)
        }
    }

    private var currentUri: Uri? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false
    private var activeHeader: EncryptedFileHeader? = null

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        val (header, _) = sessionState.ensureHeaderAndKey()
        activeHeader = header

        val plaintextSize = header.plaintextSize
        if (dataSpec.position > plaintextSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        currentPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            min(dataSpec.length, plaintextSize - currentPosition)
        } else {
            plaintextSize - currentPosition
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val header = activeHeader ?: throw IOException("DataSource not opened")
        val chunkSize = header.chunkSize.toLong()
        val frameIndex = (currentPosition / chunkSize).toInt()
        val offsetInFrame = (currentPosition % chunkSize).toInt()

        val framePlaintext = sessionState.getDecryptedFrame(frameIndex)
        val availableInFrame = framePlaintext.size - offsetInFrame
        if (availableInFrame <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        val toCopy = minOf(
            length.toLong(),
            bytesRemaining,
            availableInFrame.toLong()
        ).toInt()

        System.arraycopy(framePlaintext, offsetInFrame, buffer, offset, toCopy)
        currentPosition += toCopy
        bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

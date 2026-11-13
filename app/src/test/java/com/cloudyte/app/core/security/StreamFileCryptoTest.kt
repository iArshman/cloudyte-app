package com.cloudyte.app.core.security

import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class StreamFileCryptoTest {

    private lateinit var tempDir: File
    private lateinit var keyManager: ContentKeyManager
    private lateinit var keyMaterial: ContentKeyMaterial
    private lateinit var crypto: StreamFileCrypto
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "crypto_test_${System.nanoTime()}").apply { mkdirs() }
        val masterWrapper = JvmMemoryMasterKeyWrapper()
        keyManager = ContentKeyManager(
            cryptoDir = File(tempDir, "crypto"),
            masterKeyWrapper = masterWrapper
        )
        keyMaterial = keyManager.getOrCreateContentKey()
        crypto = StreamFileCrypto()
    }

    @After
    fun tearDown() {
        keyMaterial.destroy()
        tempDir.deleteRecursively()
    }

    private fun assertRoundTrip(plaintext: ByteArray, chunkSize: Int = CryptoConstants.MIN_CHUNK_SIZE_BYTES) {
        val encOut = ByteArrayOutputStream()
        val encResult = crypto.encryptStream(
            input = ByteArrayInputStream(plaintext),
            output = encOut,
            keyMaterial = keyMaterial,
            plaintextSize = plaintext.size.toLong(),
            chunkSize = chunkSize
        )
        val ciphertext = encOut.toByteArray()
        assertEquals(encResult.encryptedSizeBytes, ciphertext.size.toLong())
        assertEquals(plaintext.size.toLong(), encResult.plaintextSizeBytes)

        val header = crypto.inspectEncryptedHeader(ByteArrayInputStream(ciphertext))
        assertEquals(CryptoConstants.FORMAT_VERSION_V1, header.formatVersion)
        assertEquals(chunkSize, header.chunkSize)
        assertEquals(plaintext.size.toLong(), header.plaintextSize)
        assertEquals(keyMaterial.keyId, header.keyId)

        val decOut = ByteArrayOutputStream()
        val decHeader = crypto.decryptStream(
            input = ByteArrayInputStream(ciphertext),
            output = decOut,
            keyMaterial = keyMaterial
        )
        assertEquals(plaintext.size.toLong(), decHeader.plaintextSize)
        assertArrayEquals(plaintext, decOut.toByteArray())
    }

    @Test
    fun `roundTrip 0 byte empty file`() {
        assertRoundTrip(ByteArray(0))
    }

    @Test
    fun `roundTrip 1 byte file`() {
        assertRoundTrip(byteArrayOf(0x42))
    }

    @Test
    fun `roundTrip small file`() {
        val data = "Confidential Cloudyte client-side encrypted document.".toByteArray(Charsets.UTF_8)
        assertRoundTrip(data)
    }

    @Test
    fun `roundTrip exact 1 MiB chunk size file`() {
        val chunkSize = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES
        val data = ByteArray(chunkSize).also { SecureRandom().nextBytes(it) }
        assertRoundTrip(data, chunkSize = chunkSize)
    }

    @Test
    fun `roundTrip 1 MiB plus 1 byte file`() {
        val chunkSize = CryptoConstants.DEFAULT_CHUNK_SIZE_BYTES
        val data = ByteArray(chunkSize + 1).also { SecureRandom().nextBytes(it) }
        assertRoundTrip(data, chunkSize = chunkSize)
    }

    @Test
    fun `roundTrip multi chunk streaming file`() {
        val chunkSize = CryptoConstants.MIN_CHUNK_SIZE_BYTES // 64 KiB
        val data = ByteArray(chunkSize * 3 + 12345).also { SecureRandom().nextBytes(it) }
        assertRoundTrip(data, chunkSize = chunkSize)
    }

    @Test
    fun `wrong key fails authentication`() {
        val plaintext = "Secret payload".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val ciphertext = encOut.toByteArray()

        val wrongKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrongKeyMaterial = ContentKeyMaterial(keyMaterial.keyId, wrongKeyBytes)
        try {
            crypto.decryptStream(ByteArrayInputStream(ciphertext), ByteArrayOutputStream(), wrongKeyMaterial)
            fail("Expected FrameAuthenticationException")
        } catch (expected: FrameAuthenticationException) {
            // Expected
        } finally {
            wrongKeyMaterial.destroy()
        }
    }

    @Test
    fun `wrong key ID fails with WrongKeyIdException`() {
        val plaintext = "Secret payload".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val ciphertext = encOut.toByteArray()

        val differentKeyIdMaterial = ContentKeyMaterial("k_different_key_id_0000", keyMaterial.copyKeyBytes())
        try {
            crypto.decryptStream(
                input = ByteArrayInputStream(ciphertext),
                output = ByteArrayOutputStream(),
                keyMaterial = differentKeyIdMaterial
            )
            fail("Expected WrongKeyIdException")
        } catch (expected: WrongKeyIdException) {
            // Expected
        } finally {
            differentKeyIdMaterial.destroy()
        }
    }

    @Test
    fun `modified magic bytes fails with InvalidEncryptedHeaderException`() {
        val plaintext = "Test magic".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        tampered[0] = 'X'.code.toByte()

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected InvalidEncryptedHeaderException")
        } catch (expected: InvalidEncryptedHeaderException) {
            // Expected
        }
    }

    @Test
    fun `unsupported version fails with UnsupportedEncryptionVersionException`() {
        val plaintext = "Test version".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        tampered[4] = 99.toByte()

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected UnsupportedEncryptionVersionException")
        } catch (expected: UnsupportedEncryptionVersionException) {
            assertEquals(99, expected.version)
        }
    }

    @Test
    fun `truncated header fails with InvalidEncryptedHeaderException`() {
        val truncated = ByteArray(16)
        System.arraycopy(CryptoConstants.MAGIC_BYTES, 0, truncated, 0, 4)
        truncated[4] = CryptoConstants.FORMAT_VERSION_V1.toByte()
        try {
            crypto.decryptStream(ByteArrayInputStream(truncated), ByteArrayOutputStream(), keyMaterial)
            fail("Expected InvalidEncryptedHeaderException")
        } catch (expected: InvalidEncryptedHeaderException) {
            // Expected
        }
    }

    @Test
    fun `modified chunkSize in header fails authentication`() {
        val plaintext = "Header AAD binding test".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(
            ByteArrayInputStream(plaintext),
            encOut,
            keyMaterial,
            plaintext.size.toLong(),
            chunkSize = CryptoConstants.MIN_CHUNK_SIZE_BYTES
        )
        val tampered = encOut.toByteArray()
        // Header layout: magic(4) + version(1) + flags(1) + fileSalt(8) = offset 14 for chunkSize(4)
        ByteBuffer.wrap(tampered, 14, 4).order(ByteOrder.BIG_ENDIAN).putInt(128 * 1024)

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected FrameAuthenticationException")
        } catch (expected: FrameAuthenticationException) {
            // Expected because header bytes are bound in AAD
        }
    }

    @Test
    fun `modified plaintextSize in header fails authentication`() {
        val plaintext = "Header plaintextSize AAD test".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        // plaintextSize(8) is at offset 18..25
        ByteBuffer.wrap(tampered, 18, 8).order(ByteOrder.BIG_ENDIAN).putLong(plaintext.size.toLong() + 1L)

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CryptoException")
        } catch (expected: CryptoException) {
            // Expected
        }
    }

    @Test
    fun `modified frame length fails`() {
        val plaintext = "Frame length test".toByteArray()
        val encOut = ByteArrayOutputStream()
        val encRes = crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        // Frame 0 starts at header.serializedSizeBytes: [frameIndex:4][plaintextLength:4][isFinal:1]
        val ptLenOffset = encRes.header.serializedSizeBytes + 4
        ByteBuffer.wrap(tampered, ptLenOffset, 4).order(ByteOrder.BIG_ENDIAN).putInt(plaintext.size - 1)

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CryptoException")
        } catch (expected: CryptoException) {
            // Expected
        }
    }

    @Test
    fun `modified ciphertext byte fails with FrameAuthenticationException`() {
        val plaintext = "Ciphertext integrity test".toByteArray()
        val encOut = ByteArrayOutputStream()
        val encRes = crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        val ciphertextByteOffset = encRes.header.serializedSizeBytes + 9
        tampered[ciphertextByteOffset] = (tampered[ciphertextByteOffset].toInt() xor 0xFF).toByte()

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected FrameAuthenticationException")
        } catch (expected: FrameAuthenticationException) {
            // Expected
        }
    }

    @Test
    fun `modified GCM tag byte fails with FrameAuthenticationException`() {
        val plaintext = "GCM authentication tag test".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val tampered = encOut.toByteArray()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()

        try {
            crypto.decryptStream(ByteArrayInputStream(tampered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected FrameAuthenticationException")
        } catch (expected: FrameAuthenticationException) {
            // Expected
        }
    }

    @Test
    fun `truncated frame fails with CorruptedCiphertextException`() {
        val plaintext = "Truncated frame payload".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val full = encOut.toByteArray()
        val truncated = full.copyOf(full.size - 5)

        try {
            crypto.decryptStream(ByteArrayInputStream(truncated), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CorruptedCiphertextException")
        } catch (expected: CorruptedCiphertextException) {
            // Expected
        }
    }

    @Test
    fun `missing final frame fails with CorruptedCiphertextException`() {
        val chunkSize = CryptoConstants.MIN_CHUNK_SIZE_BYTES
        val plaintext = ByteArray(chunkSize * 2).also { SecureRandom().nextBytes(it) }
        val encOut = ByteArrayOutputStream()
        val encRes = crypto.encryptStream(
            ByteArrayInputStream(plaintext),
            encOut,
            keyMaterial,
            plaintext.size.toLong(),
            chunkSize = chunkSize
        )
        val full = encOut.toByteArray()

        val firstFrameEnd = encRes.header.serializedSizeBytes + 9 + chunkSize + CryptoConstants.GCM_TAG_LENGTH_BYTES
        val missingSecondFrame = full.copyOf(firstFrameEnd)

        try {
            crypto.decryptStream(ByteArrayInputStream(missingSecondFrame), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CorruptedCiphertextException")
        } catch (expected: CorruptedCiphertextException) {
            // Expected
        }
    }

    @Test
    fun `extra trailing bytes after final frame fails with CorruptedCiphertextException`() {
        val plaintext = "Trailing bytes test".toByteArray()
        val encOut = ByteArrayOutputStream()
        crypto.encryptStream(ByteArrayInputStream(plaintext), encOut, keyMaterial, plaintext.size.toLong())
        val valid = encOut.toByteArray()
        val withTrailing = valid + byteArrayOf(0x01, 0x02)

        try {
            crypto.decryptStream(ByteArrayInputStream(withTrailing), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CorruptedCiphertextException")
        } catch (expected: CorruptedCiphertextException) {
            // Expected
        }
    }

    @Test
    fun `reordered frames and duplicate frames fail authentication`() {
        val chunkSize = CryptoConstants.MIN_CHUNK_SIZE_BYTES
        val plaintext = ByteArray(chunkSize * 2).also { SecureRandom().nextBytes(it) }
        val encOut = ByteArrayOutputStream()
        val encRes = crypto.encryptStream(
            ByteArrayInputStream(plaintext),
            encOut,
            keyMaterial,
            plaintext.size.toLong(),
            chunkSize = chunkSize
        )
        val full = encOut.toByteArray()

        val headerSize = encRes.header.serializedSizeBytes
        val headerBytes = full.copyOfRange(0, headerSize)
        val frameSize = 9 + chunkSize + CryptoConstants.GCM_TAG_LENGTH_BYTES
        val frame0 = full.copyOfRange(headerSize, headerSize + frameSize)
        val frame1 = full.copyOfRange(headerSize + frameSize, full.size)

        // Reordered: frame1 followed by frame0
        val reordered = headerBytes + frame1 + frame0
        try {
            crypto.decryptStream(ByteArrayInputStream(reordered), ByteArrayOutputStream(), keyMaterial)
            fail("Expected CryptoException on reordered frames")
        } catch (expected: CryptoException) {
            // Expected
        }

        // Duplicate: frame0 followed by frame0 (with frameIndex=1 and isFinal=1 on second frame0)
        val duplicateFrame0 = frame0.copyOf()
        ByteBuffer.wrap(duplicateFrame0, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(1)
        duplicateFrame0[8] = 1.toByte()
        val duplicated = headerBytes + frame0 + duplicateFrame0
        try {
            crypto.decryptStream(ByteArrayInputStream(duplicated), ByteArrayOutputStream(), keyMaterial)
            fail("Expected FrameAuthenticationException on duplicated frame")
        } catch (expected: CryptoException) {
            // Expected
        }
    }

    @Test
    fun `encryptUriToStagingFile works for file URI and cleans up temp file on failure`() {
        val sourceFile = File(tempDir, "sample_input.txt")
        val content = "File URI encryption staging test content".toByteArray()
        sourceFile.writeBytes(content)

        val result = crypto.encryptUriToStagingFile(
            context = context,
            sourceUri = Uri.fromFile(sourceFile),
            transferId = "transfer-1",
            keyMaterial = keyMaterial
        )

        val stagingFile = result.stagingFile
        assertTrue(stagingFile.exists())
        assertEquals(stagingFile.length(), result.encryptedSizeBytes)
        val validatedHeader = crypto.validateEncryptedFile(stagingFile, expectedKeyId = keyMaterial.keyId)
        assertEquals(content.size.toLong(), validatedHeader.plaintextSize)

        // Decrypt to plaintext file and verify
        val decryptedDest = File(tempDir, "decrypted_output.txt")
        crypto.decryptEncryptedFileToPlaintext(
            encryptedFile = stagingFile,
            destinationTempFile = decryptedDest,
            keyMaterial = keyMaterial
        )
        assertArrayEquals(content, decryptedDest.readBytes())

        // Corrupt staging file and verify decryptEncryptedFileToPlaintext deletes destinationTempFile
        RandomAccessFile(stagingFile, "rw").use { raf ->
            raf.seek(raf.length() - 2)
            raf.writeByte(0xFF)
        }
        val corruptedDest = File(tempDir, "corrupted_output.txt")
        try {
            crypto.decryptEncryptedFileToPlaintext(
                encryptedFile = stagingFile,
                destinationTempFile = corruptedDest,
                keyMaterial = keyMaterial
            )
            fail("Expected FrameAuthenticationException")
        } catch (expected: FrameAuthenticationException) {
            assertFalse(corruptedDest.exists())
        } finally {
            stagingFile.delete()
        }
    }
}

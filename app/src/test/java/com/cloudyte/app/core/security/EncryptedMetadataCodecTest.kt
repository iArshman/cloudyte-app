package com.cloudyte.app.core.security

import com.cloudyte.app.api.CloudFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EncryptedMetadataCodecTest {

    private lateinit var tempDir: File
    private lateinit var keyManager: ContentKeyManager
    private lateinit var keyMaterial: ContentKeyMaterial
    private lateinit var codec: EncryptedMetadataCodec

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cloudyte_meta_test_${System.nanoTime()}").apply { mkdirs() }
        keyManager = ContentKeyManager(
            cryptoDir = File(tempDir, "crypto"),
            masterKeyWrapper = JvmMemoryMasterKeyWrapper()
        )
        keyMaterial = keyManager.getOrCreateContentKey()
        codec = EncryptedMetadataCodec()
    }

    @After
    fun tearDown() {
        keyMaterial.destroy()
        tempDir.deleteRecursively()
    }

    @Test
    fun `encrypts and decrypts metadata envelope roundTrip`() {
        val original = PlaintextFileMetadata(
            originalName = "family_vacation_photo.jpg",
            originalMimeType = "image/jpeg",
            originalExtension = "jpg",
            folderId = "folder_photos",
            relativePath = "/photos/family_vacation_photo.jpg",
            plaintextSizeBytes = 4_194_304L,
            plaintextChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            createdAt = 1_710_000_000_000L,
            modifiedAt = 1_710_000_000_000L
        )

        val envelope = codec.encryptMetadata(original, keyMaterial)
        assertEquals(CryptoConstants.METADATA_VERSION_V1, envelope.metadataVersion)
        assertEquals(keyMaterial.keyId, envelope.keyId)
        assertFalse(envelope.ciphertext.contains("family_vacation_photo"))
        assertFalse(envelope.ciphertext.contains("image/jpeg"))

        val json = codec.serializeEnvelopeToJson(envelope)
        assertFalse(json.contains("family_vacation_photo"))
        assertFalse(json.contains("image/jpeg"))

        val parsedEnvelope = codec.deserializeEnvelopeFromJson(json)
        assertNotNull(parsedEnvelope)
        val decrypted = codec.decryptMetadata(parsedEnvelope, keyMaterial)
        assertEquals(original, decrypted)
    }

    @Test
    fun `corrupted metadata ciphertext or tag fails with MetadataDecryptionException`() {
        val original = PlaintextFileMetadata(
            originalName = "tax_return_2026.pdf",
            originalMimeType = "application/pdf",
            originalExtension = "pdf",
            plaintextSizeBytes = 123_456L,
            plaintextChecksum = "abcd",
            createdAt = 1_710_000_000_000L,
            modifiedAt = 1_710_000_000_000L
        )
        val envelope = codec.encryptMetadata(original, keyMaterial)
        val rawCipher = Base64UrlCodec.decode(envelope.ciphertext)
        rawCipher[rawCipher.lastIndex] = (rawCipher[rawCipher.lastIndex].toInt() xor 0xFF).toByte()
        val tamperedEnvelope = envelope.copy(ciphertext = Base64UrlCodec.encode(rawCipher))

        try {
            codec.decryptMetadata(tamperedEnvelope, keyMaterial)
            fail("Expected MetadataDecryptionException")
        } catch (expected: MetadataDecryptionException) {
            // Expected
        }
    }

    @Test
    fun `wrong keyId in metadata envelope fails with WrongKeyIdException`() {
        val original = PlaintextFileMetadata(
            originalName = "notes.txt",
            originalMimeType = "text/plain",
            originalExtension = "txt",
            plaintextSizeBytes = 512L,
            plaintextChecksum = "1234",
            createdAt = 1_710_000_000_000L,
            modifiedAt = 1_710_000_000_000L
        )
        val envelope = codec.encryptMetadata(original, keyMaterial)
        val wrongKeyIdEnvelope = envelope.copy(keyId = "k_different_id")

        try {
            codec.decryptMetadata(wrongKeyIdEnvelope, keyMaterial)
            fail("Expected WrongKeyIdException")
        } catch (expected: WrongKeyIdException) {
            // Expected
        }
    }

    @Test
    fun `CloudFile fallback displays Encrypted file and never guesses MIME or extension when metadata unavailable`() {
        val lockedCloudFile = CloudFile(
            id = "remote_1",
            name = "cyte_9f8e7d6c5b4a.bin",
            type = "bin",
            mimeType = "application/octet-stream",
            size = 1048650L,
            isEncrypted = true,
            encryptionFormatVersion = 1,
            encryptionKeyId = "k_123",
            encryptedSizeBytes = 1048650L,
            opaqueRemoteName = "cyte_9f8e7d6c5b4a.bin",
            originalMetadataAvailable = false
        )

        assertEquals("Encrypted file", lockedCloudFile.effectiveName)
        assertEquals("encrypted", lockedCloudFile.effectiveType)
        assertFalse(lockedCloudFile.isImage)
        assertFalse(lockedCloudFile.isVideo)
        assertFalse(lockedCloudFile.isAudio)
        assertFalse(lockedCloudFile.isPdf)
        assertFalse(lockedCloudFile.isTextOrCode)

        // Once metadata is decrypted locally:
        val unlockedCloudFile = lockedCloudFile.copy(
            name = "holiday_clip.mp4",
            mimeType = "video/mp4",
            plaintextSizeBytes = 1048576L,
            originalMetadataAvailable = true
        )
        assertEquals("holiday_clip.mp4", unlockedCloudFile.effectiveName)
        assertEquals("mp4", unlockedCloudFile.effectiveType)
        assertTrue(unlockedCloudFile.isVideo)
        assertEquals(1048576L, unlockedCloudFile.sizeBytes)
    }
}

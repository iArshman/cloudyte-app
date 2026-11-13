package com.cloudyte.app.core.security

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class ContentKeyManagerAndBackupTest {

    private lateinit var tempDir: File
    private lateinit var cryptoDir: File
    private lateinit var keyFile: File
    private lateinit var masterKeyWrapper: JvmMemoryMasterKeyWrapper
    private lateinit var keyManager: ContentKeyManager
    private lateinit var backupCodec: KeyBackupCodec

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "cloudyte_key_test_${System.nanoTime()}").apply { mkdirs() }
        cryptoDir = File(tempDir, "crypto")
        keyFile = File(cryptoDir, CryptoConstants.WRAPPED_KEY_FILE)
        masterKeyWrapper = JvmMemoryMasterKeyWrapper()
        keyManager = ContentKeyManager(cryptoDir = cryptoDir, masterKeyWrapper = masterKeyWrapper)
        backupCodec = KeyBackupCodec(keyManager)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `generates 256-bit CONTENT_KEY and unwraps consistently`() {
        assertFalse(keyManager.hasWrappedKeyFile())

        val created = keyManager.getOrCreateContentKey()
        assertTrue(keyManager.hasWrappedKeyFile())
        assertEquals(CryptoConstants.AES_KEY_SIZE_BYTES, created.copyKeyBytes().size)
        assertTrue(created.keyId.startsWith("k_"))

        // Clear memory cache and reload from wrapped file on disk
        keyManager.clearInMemoryCacheForTest()
        val reloaded = keyManager.getExistingContentKey()
        assertEquals(created.keyId, reloaded.keyId)
        assertArrayEquals(created.copyKeyBytes(), reloaded.copyKeyBytes())
    }

    @Test
    fun `unwrap failure throws KeyUnavailableException and NEVER overwrites existing key file`() {
        val original = keyManager.getOrCreateContentKey()
        val originalWrappedFileBytes = keyFile.readBytes()

        // Simulate Keystore master key invalidation / change
        val differentMasterWrapper = JvmMemoryMasterKeyWrapper()
        val brokenKeyManager = ContentKeyManager(
            cryptoDir = cryptoDir,
            masterKeyWrapper = differentMasterWrapper
        )

        try {
            brokenKeyManager.getOrCreateContentKey()
            fail("Expected KeyUnavailableException when existing key cannot be unwrapped")
        } catch (expected: KeyUnavailableException) {
            // Verify wrapped key file was NOT overwritten or deleted
            assertTrue(keyFile.exists())
            assertArrayEquals(originalWrappedFileBytes, keyFile.readBytes())
        }

        // Verify original master key can still unwrap the untouched file
        val recoveredManager = ContentKeyManager(cryptoDir = cryptoDir, masterKeyWrapper = masterKeyWrapper)
        val recovered = recoveredManager.getExistingContentKey()
        assertEquals(original.keyId, recovered.keyId)
        assertArrayEquals(original.copyKeyBytes(), recovered.copyKeyBytes())
    }

    @Test
    fun `PassphraseKdf derives 256-bit key with PBKDF2-HMAC-SHA256 310000 iterations`() {
        val salt1 = PassphraseKdf.generateSalt()
        val salt2 = PassphraseKdf.generateSalt()
        assertEquals(CryptoConstants.PBKDF2_SALT_LENGTH_BYTES, salt1.size)

        val key1a = PassphraseKdf.deriveKey("correct-horse-battery-staple".toCharArray(), salt1)
        val key1b = PassphraseKdf.deriveKey("correct-horse-battery-staple".toCharArray(), salt1)
        val key2 = PassphraseKdf.deriveKey("correct-horse-battery-staple".toCharArray(), salt2)
        val key3 = PassphraseKdf.deriveKey("different-passphrase".toCharArray(), salt1)

        assertEquals(CryptoConstants.AES_KEY_SIZE_BYTES, key1a.size)
        assertArrayEquals(key1a, key1b)
        assertFalse(key1a.contentEquals(key2))
        assertFalse(key1a.contentEquals(key3))
    }

    @Test
    fun `KeyBackupCodec roundTrip restores exact CONTENT_KEY and keyId`() {
        val original = keyManager.getOrCreateContentKey()
        val passphrase = "MyStrongRecoveryPassphrase!2026"

        val backupBytes = backupCodec.createKeyBackup(passphrase.toCharArray())
        assertTrue(backupCodec.validateKeyBackup(backupBytes))

        // Restore using restoreKeyBackup
        val restoredRaw = backupCodec.restoreKeyBackup(passphrase.toCharArray(), backupBytes)
        assertNotNull(restoredRaw)
        assertArrayEquals(original.copyKeyBytes(), restoredRaw!!)

        // Restore into a fresh device ContentKeyManager
        val newDeviceCryptoDir = File(tempDir, "new_device_crypto")
        val newDeviceKeyManager = ContentKeyManager(
            cryptoDir = newDeviceCryptoDir,
            masterKeyWrapper = JvmMemoryMasterKeyWrapper()
        )
        val newDeviceBackupCodec = KeyBackupCodec(newDeviceKeyManager)
        val detailed = newDeviceBackupCodec.restoreKeyBackupDetailed(passphrase.toCharArray(), backupBytes)
        newDeviceKeyManager.importRecoveredContentKey(detailed.keyId, detailed.contentKeyBytes, detailed.createdAt)

        val loadedOnNewDevice = newDeviceKeyManager.getExistingContentKey()
        assertEquals(original.keyId, loadedOnNewDevice.keyId)
        assertArrayEquals(original.copyKeyBytes(), loadedOnNewDevice.copyKeyBytes())
    }

    @Test
    fun `KeyBackupCodec wrong passphrase returns null and throws InvalidPassphraseException`() {
        keyManager.getOrCreateContentKey()
        val backupBytes = backupCodec.createKeyBackup("RightPassphrase123".toCharArray())

        val result = backupCodec.restoreKeyBackup("WrongPassphrase999".toCharArray(), backupBytes)
        assertNull(result)

        try {
            backupCodec.restoreKeyBackupDetailed("WrongPassphrase999".toCharArray(), backupBytes)
            fail("Expected InvalidPassphraseException")
        } catch (expected: InvalidPassphraseException) {
            // Expected
        }
    }

    @Test
    fun `KeyBackupCodec rejects corrupted JSON, unsupported version, and truncated fields`() {
        keyManager.getOrCreateContentKey()
        val validJson = String(backupCodec.createKeyBackup("Pass123!".toCharArray()), Charsets.UTF_8)

        // 1. Corrupted JSON
        assertFalse(backupCodec.validateKeyBackup("{not_valid_json".toByteArray()))
        assertNull(backupCodec.restoreKeyBackup("Pass123!".toCharArray(), "{not_valid_json".toByteArray()))

        // 2. Unsupported backup version
        val unsupportedVersionJson = validJson.replace("\"backupVersion\":1", "\"backupVersion\":99")
            .replace("\"backupVersion\": 1", "\"backupVersion\": 99")
        assertFalse(backupCodec.validateKeyBackup(unsupportedVersionJson.toByteArray()))
        try {
            backupCodec.restoreKeyBackupDetailed("Pass123!".toCharArray(), unsupportedVersionJson.toByteArray())
            fail("Expected UnsupportedEncryptionVersionException for unsupported version")
        } catch (expected: UnsupportedEncryptionVersionException) {
            assertEquals(99, expected.version)
        }

        // 3. Truncated salt
        val truncatedSaltJson = """
            {"backupVersion":1,"keyId":"k_123","kdfAlgorithm":"PBKDF2-HMAC-SHA256","kdfIterations":310000,"salt":"AAAA","wrapAlgorithm":"AES-256-GCM","nonce":"AAAAAAAAAAAAAAAA","wrappedKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
        """.trimIndent()
        assertFalse(backupCodec.validateKeyBackup(truncatedSaltJson.toByteArray()))

        // 4. Tampered keyId (AAD mismatch)
        val tamperedKeyIdJson = validJson.replace("k_", "k_tampered_")
        assertNull(backupCodec.restoreKeyBackup("Pass123!".toCharArray(), tamperedKeyIdJson.toByteArray()))
    }
}

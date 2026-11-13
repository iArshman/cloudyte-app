package com.cloudyte.app.core.security

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class RestoredKeyBackup(
    val keyId: String,
    val contentKeyBytes: ByteArray,
    val createdAt: Long
) {
    fun destroy() {
        contentKeyBytes.fill(0)
    }

    override fun toString(): String = "RestoredKeyBackup(keyId=$keyId, contentKeyBytes=<REDACTED>, createdAt=$createdAt)"
}

/**
 * Encodes and decodes password-protected recovery backups for the 256-bit CONTENT_KEY.
 *
 * Backup JSON schema:
 * {
 *   "backupVersion": 1,
 *   "keyId": "opaque-key-id",
 *   "kdfAlgorithm": "PBKDF2-HMAC-SHA256",
 *   "kdfIterations": 310000,
 *   "salt": "base64url",
 *   "wrapAlgorithm": "AES-256-GCM",
 *   "nonce": "base64url",
 *   "wrappedKey": "base64url"
 * }
 */
class KeyBackupCodec(
    private val contentKeyProvider: ContentKeyProvider? = null,
    private val secureRandom: SecureRandom = SecureRandom()
) {

    /**
     * Creates an encrypted key backup JSON byte array using the current or created CONTENT_KEY
     * from [contentKeyProvider].
     */
    @Throws(CryptoException::class)
    fun createKeyBackup(passphrase: CharArray): ByteArray {
        val provider = contentKeyProvider
            ?: throw KeyUnavailableException("No ContentKeyProvider configured for KeyBackupCodec")
        val keyMaterial = provider.getExistingContentKey()
        return try {
            val backupBytes = createKeyBackupForMaterial(passphrase, keyMaterial)
            provider.markRecoveryBackupCreated()
            backupBytes
        } finally {
            keyMaterial.destroy()
        }
    }

    /**
     * Creates an encrypted key backup JSON byte array for the given [keyMaterial].
     */
    @Throws(CryptoException::class)
    fun createKeyBackupForMaterial(
        passphrase: CharArray,
        keyMaterial: ContentKeyMaterial
    ): ByteArray {
        if (passphrase.isEmpty()) {
            throw InvalidPassphraseException("Recovery passphrase must not be empty")
        }
        val rawKeyBytes = keyMaterial.copyKeyBytes()
        val salt = PassphraseKdf.generateSalt(secureRandom)
        val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)

        val derivedKey = PassphraseKdf.deriveKey(
            passphrase = passphrase,
            salt = salt,
            iterations = CryptoConstants.PBKDF2_ITERATIONS
        )

        try {
            val version = CryptoConstants.BACKUP_VERSION_V1
            val kdfName = CryptoConstants.PBKDF2_HEADER_NAME
            val iterations = CryptoConstants.PBKDF2_ITERATIONS
            val aad = buildBackupAad(
                version = version,
                kdf = kdfName,
                iterations = iterations,
                keyId = keyMaterial.keyId
            )

            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derivedKey, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(aad)
            val ciphertextWithTag = cipher.doFinal(rawKeyBytes)

            val json = JSONObject().apply {
                put("backupVersion", version)
                put("keyId", keyMaterial.keyId)
                put("kdfAlgorithm", kdfName)
                put("kdfIterations", iterations)
                put("salt", Base64UrlCodec.encode(salt))
                put("wrapAlgorithm", CryptoConstants.METADATA_ALGORITHM_NAME)
                put("nonce", Base64UrlCodec.encode(nonce))
                put("wrappedKey", Base64UrlCodec.encode(ciphertextWithTag))
            }
            return json.toString().toByteArray(StandardCharsets.UTF_8)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw KeyBackupException("Failed to create encrypted key backup", e)
        } finally {
            rawKeyBytes.fill(0)
            derivedKey.fill(0)
        }
    }

    /**
     * Restores the raw 32-byte CONTENT_KEY from [backup] using [passphrase].
     * If [contentKeyProvider] is present, also imports the restored key into the provider.
     *
     * Returns `null` if the passphrase is wrong or if the backup fails validation/authentication.
     */
    fun restoreKeyBackup(passphrase: CharArray, backup: ByteArray): ByteArray? {
        return try {
            val restored = restoreKeyBackupDetailed(passphrase, backup)
            contentKeyProvider?.importRecoveredContentKey(
                keyId = restored.keyId,
                rawKeyBytes = restored.contentKeyBytes,
                createdAt = restored.createdAt
            )?.destroy()
            restored.contentKeyBytes
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Restores the full [RestoredKeyBackup] (including `keyId`, `contentKeyBytes`, and `createdAt`),
     * throwing typed [CryptoException]s on invalid structure or wrong passphrase.
     */
    @Throws(CryptoException::class)
    fun restoreKeyBackupDetailed(passphrase: CharArray, backup: ByteArray): RestoredKeyBackup {
        if (passphrase.isEmpty()) {
            throw InvalidPassphraseException("Recovery passphrase must not be empty")
        }
        val parsed = parseBackupEnvelope(backup)
        val derivedKey = PassphraseKdf.deriveKey(
            passphrase = passphrase,
            salt = parsed.salt,
            iterations = parsed.iterations
        )
        try {
            val aad = buildBackupAad(
                version = parsed.version,
                kdf = parsed.kdf,
                iterations = parsed.iterations,
                keyId = parsed.keyId
            )
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derivedKey, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, parsed.nonce)
            )
            cipher.updateAAD(aad)
            val plaintextKey = cipher.doFinal(parsed.ciphertextWithTag)
            if (plaintextKey.size != CryptoConstants.AES_KEY_SIZE_BYTES) {
                plaintextKey.fill(0)
                throw KeyBackupException("Decrypted key backup has invalid key size: ${plaintextKey.size}")
            }
            contentKeyProvider?.importRecoveredContentKey(
                keyId = parsed.keyId,
                rawKeyBytes = plaintextKey,
                createdAt = 0L
            )?.destroy()
            return RestoredKeyBackup(
                keyId = parsed.keyId,
                contentKeyBytes = plaintextKey,
                createdAt = 0L
            )
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw InvalidPassphraseException("Wrong recovery passphrase or corrupted key backup", e)
        } finally {
            derivedKey.fill(0)
        }
    }

    /**
     * Validates that [backup] is a well-formed version 1 recovery key backup JSON
     * with valid PBKDF2 parameters, salt, nonce, ciphertext+tag lengths, and keyId.
     */
    fun validateKeyBackup(backup: ByteArray): Boolean {
        return try {
            parseBackupEnvelope(backup)
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class ParsedBackupEnvelope(
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertextWithTag: ByteArray,
        val keyId: String
    )

    private fun parseBackupEnvelope(backup: ByteArray): ParsedBackupEnvelope {
        if (backup.isEmpty()) {
            throw KeyBackupException("Key backup is empty")
        }
        val jsonText = try {
            String(backup, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw KeyBackupException("Key backup is not valid UTF-8", e)
        }

        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw KeyBackupException("Key backup is not valid JSON", e)
        }

        if (!json.has("backupVersion")) {
            throw KeyBackupException("Key backup is missing backupVersion")
        }
        val version = json.optInt("backupVersion", -1)
        if (version != CryptoConstants.BACKUP_VERSION_V1) {
            throw UnsupportedEncryptionVersionException(version, "Unsupported key backup version: $version")
        }

        val kdf = json.optString("kdfAlgorithm", "")
        if (kdf != CryptoConstants.PBKDF2_HEADER_NAME) {
            throw KeyBackupException("Unsupported KDF in key backup: $kdf")
        }

        val iterations = json.optInt("kdfIterations", 0)
        if (iterations < CryptoConstants.PBKDF2_ITERATIONS) {
            throw KeyBackupException("Insufficient KDF iterations in key backup: $iterations")
        }

        val saltStr = json.optString("salt", "")
        val nonceStr = json.optString("nonce", "")
        val ciphertextStr = json.optString("wrappedKey", "")
        val wrapAlgorithm = json.optString("wrapAlgorithm", "")
        val keyId = json.optString("keyId", "")
        if (wrapAlgorithm != CryptoConstants.METADATA_ALGORITHM_NAME) {
            throw KeyBackupException("Unsupported wrap algorithm in key backup: $wrapAlgorithm")
        }
        if (saltStr.isBlank() || nonceStr.isBlank() || ciphertextStr.isBlank() || keyId.isBlank()) {
            throw KeyBackupException("Key backup is missing required fields")
        }

        val salt = try {
            Base64UrlCodec.decode(saltStr)
        } catch (e: Exception) {
            throw KeyBackupException("Invalid Base64 salt in key backup", e)
        }
        if (salt.size != CryptoConstants.PBKDF2_SALT_LENGTH_BYTES) {
            throw KeyBackupException("Invalid salt length in key backup: ${salt.size}")
        }

        val nonce = try {
            Base64UrlCodec.decode(nonceStr)
        } catch (e: Exception) {
            throw KeyBackupException("Invalid Base64 nonce in key backup", e)
        }
        if (nonce.size != CryptoConstants.GCM_NONCE_LENGTH_BYTES) {
            throw KeyBackupException("Invalid nonce length in key backup: ${nonce.size}")
        }

        val ciphertextWithTag = try {
            Base64UrlCodec.decode(ciphertextStr)
        } catch (e: Exception) {
            throw KeyBackupException("Invalid Base64 ciphertext in key backup", e)
        }
        val expectedCiphertextLength = CryptoConstants.AES_KEY_SIZE_BYTES + CryptoConstants.GCM_TAG_LENGTH_BYTES
        if (ciphertextWithTag.size != expectedCiphertextLength) {
            throw KeyBackupException("Invalid ciphertext length in key backup: ${ciphertextWithTag.size}")
        }

        return ParsedBackupEnvelope(
            version = version,
            kdf = kdf,
            iterations = iterations,
            salt = salt,
            nonce = nonce,
            ciphertextWithTag = ciphertextWithTag,
            keyId = keyId
        )
    }

    companion object {
        internal fun buildBackupAad(
            version: Int,
            kdf: String,
            iterations: Int,
            keyId: String
        ): ByteArray {
            val kdfBytes = kdf.toByteArray(StandardCharsets.UTF_8)
            val keyIdBytes = keyId.toByteArray(StandardCharsets.UTF_8)
            return ByteBuffer.allocate(4 + 2 + kdfBytes.size + 4 + 2 + keyIdBytes.size)
                .putInt(version)
                .putShort(kdfBytes.size.toShort())
                .put(kdfBytes)
                .putInt(iterations)
                .putShort(keyIdBytes.size.toShort())
                .put(keyIdBytes)
                .array()
        }
    }
}

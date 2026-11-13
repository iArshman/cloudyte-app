package com.cloudyte.app.core.security

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

enum class EncryptionKeyStatus {
    NOT_CREATED,
    AVAILABLE,
    UNAVAILABLE
}

enum class RecoveryBackupStatus {
    NOT_CREATED,
    CREATED,
    RESTORED
}

/**
 * Holds the in-memory 256-bit CONTENT_KEY and its opaque keyId.
 * Never logs raw key bytes in toString().
 */
class ContentKeyMaterial(
    val keyId: String,
    private val rawKey: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(keyId.isNotBlank()) { "keyId must not be blank" }
        require(rawKey.size == CryptoConstants.AES_KEY_SIZE_BYTES) {
            "CONTENT_KEY must be ${CryptoConstants.AES_KEY_SIZE_BYTES} bytes (256-bit)"
        }
    }

    fun copyKeyBytes(): ByteArray = rawKey.copyOf()

    fun destroy() {
        rawKey.fill(0)
    }

    override fun toString(): String = "ContentKeyMaterial(keyId=$keyId, keyBytes=<REDACTED>)"
}

data class WrappedContentKeyRecord(
    val formatVersion: Int,
    val keyId: String,
    val createdAt: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val authTag: ByteArray
) {
    fun ciphertextWithTag(): ByteArray = ciphertext + authTag
}

interface ContentKeyProvider {
    @Throws(CryptoException::class)
    fun getOrCreateContentKey(): ContentKeyMaterial

    @Throws(CryptoException::class)
    fun getExistingContentKey(): ContentKeyMaterial

    @Throws(CryptoException::class)
    fun getContentKeyForDecryption(expectedKeyId: String? = null): ContentKeyMaterial

    @Throws(CryptoException::class)
    fun importRecoveredContentKey(keyId: String, rawKeyBytes: ByteArray, createdAt: Long = System.currentTimeMillis()): ContentKeyMaterial

    fun getKeyStatus(): EncryptionKeyStatus
    fun getCurrentKeyIdOrNull(): String?
    fun getRecoveryBackupStatus(): RecoveryBackupStatus
    fun markRecoveryBackupCreated()
    fun hasWrappedKeyFile(): Boolean
}

/**
 * Manages generation, wrapping, storage, and retrieval of the 256-bit CONTENT_KEY.
 *
 * The raw CONTENT_KEY is NEVER stored in SharedPreferences, Room, DataStore, or plaintext files.
 * Only the master-key-wrapped payload is stored in `context.filesDir/crypto/content_key.bin`.
 *
 * Binary format of `content_key.bin`:
 * - 4 bytes magic: "CYTK"
 * - 4 bytes int: keyFormatVersion (1)
 * - 8 bytes long: createdAt epoch millis
 * - 2 bytes unsigned short: keyId UTF-8 byte length
 * - N bytes: keyId UTF-8 bytes
 * - 2 bytes unsigned short: nonce length (12)
 * - 12 bytes: GCM nonce
 * - 4 bytes int: ciphertext length (32)
 * - 32 bytes: encrypted CONTENT_KEY
 * - 2 bytes unsigned short: authentication tag length (16)
 * - 16 bytes: GCM authentication tag
 */
class ContentKeyManager(
    private val cryptoDir: File,
    private val masterKeyWrapper: MasterKeyWrapper = AndroidKeystoreManager(),
    private val secureRandom: SecureRandom = SecureRandom()
) : ContentKeyProvider {

    constructor(
        context: Context,
        masterKeyWrapper: MasterKeyWrapper = AndroidKeystoreManager(),
        secureRandom: SecureRandom = SecureRandom()
    ) : this(
        cryptoDir = File(context.filesDir, CryptoConstants.WRAPPED_KEY_DIR),
        masterKeyWrapper = masterKeyWrapper,
        secureRandom = secureRandom
    )

    private val wrappedKeyFile: File
        get() = File(cryptoDir, CryptoConstants.WRAPPED_KEY_FILE)

    private val backupStatusFile: File
        get() = File(cryptoDir, CryptoConstants.KEY_BACKUP_STATUS_FILE)

    @Volatile
    private var cachedKey: ContentKeyMaterial? = null

    @Synchronized
    override fun hasWrappedKeyFile(): Boolean = wrappedKeyFile.exists() && wrappedKeyFile.length() > 0L

    /**
     * Returns the existing CONTENT_KEY if `content_key.bin` exists, or generates and wraps a new
     * 256-bit CONTENT_KEY if no wrapped key file exists yet.
     *
     * CRITICAL SAFETY INVARIANT:
     * If `content_key.bin` already exists on disk and cannot be unwrapped (e.g., Keystore invalidated
     * or file tampered), this method MUST NOT overwrite it with a new key! It throws KeyUnavailableException.
     */
    @Synchronized
    override fun getOrCreateContentKey(): ContentKeyMaterial {
        cachedKey?.let { return ContentKeyMaterial(it.keyId, it.copyKeyBytes(), it.createdAt) }

        if (wrappedKeyFile.exists()) {
            return loadAndUnwrapExistingKey()
        }

        val newKeyBytes = ByteArray(CryptoConstants.AES_KEY_SIZE_BYTES)
        secureRandom.nextBytes(newKeyBytes)
        val keyId = generateOpaqueKeyId()
        val createdAt = System.currentTimeMillis()

        return try {
            persistWrappedKey(
                keyId = keyId,
                rawKeyBytes = newKeyBytes,
                createdAt = createdAt
            )
            val material = ContentKeyMaterial(keyId, newKeyBytes.copyOf(), createdAt)
            cachedKey = material
            ContentKeyMaterial(material.keyId, material.copyKeyBytes(), material.createdAt)
        } finally {
            newKeyBytes.fill(0)
        }
    }

    /**
     * Retrieves the existing CONTENT_KEY without ever creating a replacement key.
     * Throws KeyUnavailableException if no key exists or if unwrapping fails.
     */
    @Synchronized
    override fun getExistingContentKey(): ContentKeyMaterial {
        cachedKey?.let { return ContentKeyMaterial(it.keyId, it.copyKeyBytes(), it.createdAt) }
        if (!wrappedKeyFile.exists()) {
            throw KeyUnavailableException("No encryption key is available on this device. Restore your key from a recovery backup.")
        }
        return loadAndUnwrapExistingKey()
    }

    /**
     * Retrieves the existing CONTENT_KEY for decryption and verifies that its keyId matches
     * [expectedKeyId] if provided. Never generates a replacement key.
     */
    @Synchronized
    override fun getContentKeyForDecryption(expectedKeyId: String?): ContentKeyMaterial {
        val material = getExistingContentKey()
        if (!expectedKeyId.isNullOrBlank() && material.keyId != expectedKeyId) {
            material.destroy()
            throw WrongKeyIdException(expectedKeyId = expectedKeyId, actualKeyId = material.keyId)
        }
        return material
    }

    /**
     * Imports a recovered CONTENT_KEY (e.g. from a passphrase-protected recovery backup),
     * wraps it with the Android Keystore master key, and stores it atomically in `content_key.bin`.
     */
    @Synchronized
    override fun importRecoveredContentKey(
        keyId: String,
        rawKeyBytes: ByteArray,
        createdAt: Long
    ): ContentKeyMaterial {
        if (keyId.isBlank() || keyId.toByteArray(StandardCharsets.UTF_8).size > CryptoConstants.MAX_KEY_ID_LENGTH_BYTES) {
            throw KeyBackupException("Invalid keyId in recovered key")
        }
        if (rawKeyBytes.size != CryptoConstants.AES_KEY_SIZE_BYTES) {
            throw KeyBackupException("Invalid CONTENT_KEY length: ${rawKeyBytes.size}")
        }

        persistWrappedKey(
            keyId = keyId,
            rawKeyBytes = rawKeyBytes,
            createdAt = createdAt
        )
        cachedKey?.destroy()
        val material = ContentKeyMaterial(keyId, rawKeyBytes.copyOf(), createdAt)
        cachedKey = material
        writeBackupStatus(RecoveryBackupStatus.RESTORED, keyId)
        return ContentKeyMaterial(material.keyId, material.copyKeyBytes(), material.createdAt)
    }

    @Synchronized
    override fun getKeyStatus(): EncryptionKeyStatus {
        cachedKey?.let { return EncryptionKeyStatus.AVAILABLE }
        if (!wrappedKeyFile.exists()) {
            return EncryptionKeyStatus.NOT_CREATED
        }
        return try {
            val loaded = loadAndUnwrapExistingKey()
            loaded.destroy()
            EncryptionKeyStatus.AVAILABLE
        } catch (_: Exception) {
            EncryptionKeyStatus.UNAVAILABLE
        }
    }

    @Synchronized
    override fun getCurrentKeyIdOrNull(): String? {
        cachedKey?.let { return it.keyId }
        if (!wrappedKeyFile.exists()) return null
        return try {
            val record = readWrappedKeyRecord(wrappedKeyFile.readBytes())
            record.keyId
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun getRecoveryBackupStatus(): RecoveryBackupStatus {
        if (!backupStatusFile.exists()) return RecoveryBackupStatus.NOT_CREATED
        return try {
            val json = JSONObject(backupStatusFile.readText(StandardCharsets.UTF_8))
            val statusName = json.optString("status", RecoveryBackupStatus.NOT_CREATED.name)
            RecoveryBackupStatus.valueOf(statusName)
        } catch (_: Exception) {
            RecoveryBackupStatus.NOT_CREATED
        }
    }

    @Synchronized
    override fun markRecoveryBackupCreated() {
        val keyId = getCurrentKeyIdOrNull().orEmpty()
        writeBackupStatus(RecoveryBackupStatus.CREATED, keyId)
    }

    @Synchronized
    fun clearInMemoryCacheForTest() {
        cachedKey?.destroy()
        cachedKey = null
    }

    private fun writeBackupStatus(status: RecoveryBackupStatus, keyId: String) {
        try {
            if (!cryptoDir.exists()) cryptoDir.mkdirs()
            val json = JSONObject().apply {
                put("status", status.name)
                put("keyId", keyId)
                put("updatedAt", System.currentTimeMillis())
            }
            backupStatusFile.writeText(json.toString(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Non-fatal metadata indicator
        }
    }

    private fun loadAndUnwrapExistingKey(): ContentKeyMaterial {
        val fileBytes = try {
            wrappedKeyFile.readBytes()
        } catch (e: Exception) {
            throw KeyUnavailableException("Unable to read wrapped content key file", e)
        }

        val record = readWrappedKeyRecord(fileBytes)
        if (record.formatVersion != CryptoConstants.KEY_FORMAT_VERSION_V1) {
            throw UnsupportedEncryptionVersionException(
                record.formatVersion,
                "Unsupported wrapped key format version: ${record.formatVersion}"
            )
        }

        val aad = buildWrapAad(record.formatVersion, record.keyId, record.createdAt)
        val unwrappedBytes = try {
            masterKeyWrapper.unwrap(
                nonce = record.nonce,
                ciphertextWithTag = record.ciphertextWithTag(),
                aad = aad
            )
        } catch (e: CryptoException) {
            throw KeyUnavailableException("Encryption key cannot be unwrapped on this device. Restore from recovery backup.", e)
        } catch (e: Exception) {
            throw KeyUnavailableException("Encryption key cannot be unwrapped on this device. Restore from recovery backup.", e)
        }

        if (unwrappedBytes.size != CryptoConstants.AES_KEY_SIZE_BYTES) {
            unwrappedBytes.fill(0)
            throw KeyUnavailableException("Unwrapped content key has invalid size: ${unwrappedBytes.size}")
        }

        val cached = ContentKeyMaterial(record.keyId, unwrappedBytes.copyOf(), record.createdAt)
        cachedKey = cached
        return ContentKeyMaterial(record.keyId, unwrappedBytes, record.createdAt)
    }

    private fun persistWrappedKey(keyId: String, rawKeyBytes: ByteArray, createdAt: Long) {
        if (!cryptoDir.exists() && !cryptoDir.mkdirs()) {
            throw KeyUnavailableException("Failed to create private crypto directory")
        }

        val formatVersion = CryptoConstants.KEY_FORMAT_VERSION_V1
        val aad = buildWrapAad(formatVersion, keyId, createdAt)
        val wrapped = masterKeyWrapper.wrap(rawKeyBytes, aad)
        val ciphertextWithTag = wrapped.ciphertextWithTag
        if (ciphertextWithTag.size != CryptoConstants.AES_KEY_SIZE_BYTES + CryptoConstants.GCM_TAG_LENGTH_BYTES) {
            throw KeyUnwrapException("Unexpected wrapped key ciphertext+tag size: ${ciphertextWithTag.size}")
        }

        val ciphertext = ciphertextWithTag.copyOfRange(0, CryptoConstants.AES_KEY_SIZE_BYTES)
        val authTag = ciphertextWithTag.copyOfRange(
            CryptoConstants.AES_KEY_SIZE_BYTES,
            ciphertextWithTag.size
        )

        val record = WrappedContentKeyRecord(
            formatVersion = formatVersion,
            keyId = keyId,
            createdAt = createdAt,
            nonce = wrapped.nonce,
            ciphertext = ciphertext,
            authTag = authTag
        )
        val serialized = serializeWrappedKeyRecord(record)

        val tempFile = File(cryptoDir, "${CryptoConstants.WRAPPED_KEY_FILE}.tmp")
        tempFile.writeBytes(serialized)
        if (!tempFile.renameTo(wrappedKeyFile)) {
            wrappedKeyFile.writeBytes(serialized)
            tempFile.delete()
        }
    }

    private fun generateOpaqueKeyId(): String {
        val randomBytes = ByteArray(16)
        secureRandom.nextBytes(randomBytes)
        val hex = buildString(32) {
            for (b in randomBytes) {
                append(String.format("%02x", b))
            }
        }
        return "k_$hex"
    }

    companion object {
        private val WRAPPED_FILE_MAGIC = "CYTK".toByteArray(StandardCharsets.US_ASCII)

        internal fun buildWrapAad(formatVersion: Int, keyId: String, createdAt: Long): ByteArray {
            val keyIdBytes = keyId.toByteArray(StandardCharsets.UTF_8)
            return ByteBuffer.allocate(4 + 4 + 8 + 2 + keyIdBytes.size)
                .put(WRAPPED_FILE_MAGIC)
                .putInt(formatVersion)
                .putLong(createdAt)
                .putShort(keyIdBytes.size.toShort())
                .put(keyIdBytes)
                .array()
        }

        internal fun serializeWrappedKeyRecord(record: WrappedContentKeyRecord): ByteArray {
            val keyIdBytes = record.keyId.toByteArray(StandardCharsets.UTF_8)
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { out ->
                out.write(WRAPPED_FILE_MAGIC)
                out.writeInt(record.formatVersion)
                out.writeLong(record.createdAt)
                out.writeShort(keyIdBytes.size)
                out.write(keyIdBytes)
                out.writeShort(record.nonce.size)
                out.write(record.nonce)
                out.writeInt(record.ciphertext.size)
                out.write(record.ciphertext)
                out.writeShort(record.authTag.size)
                out.write(record.authTag)
            }
            return baos.toByteArray()
        }

        internal fun readWrappedKeyRecord(bytes: ByteArray): WrappedContentKeyRecord {
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val magic = ByteArray(4)
                    input.readFully(magic)
                    if (!magic.contentEquals(WRAPPED_FILE_MAGIC)) {
                        throw KeyUnavailableException("Malformed wrapped key file magic")
                    }
                    val formatVersion = input.readInt()
                    val createdAt = input.readLong()
                    val keyIdLen = input.readUnsignedShort()
                    if (keyIdLen <= 0 || keyIdLen > CryptoConstants.MAX_KEY_ID_LENGTH_BYTES) {
                        throw KeyUnavailableException("Invalid keyId length in wrapped key file: $keyIdLen")
                    }
                    val keyIdBytes = ByteArray(keyIdLen)
                    input.readFully(keyIdBytes)
                    val keyId = String(keyIdBytes, StandardCharsets.UTF_8)

                    val nonceLen = input.readUnsignedShort()
                    if (nonceLen != CryptoConstants.GCM_NONCE_LENGTH_BYTES) {
                        throw KeyUnavailableException("Invalid nonce length in wrapped key file: $nonceLen")
                    }
                    val nonce = ByteArray(nonceLen)
                    input.readFully(nonce)

                    val ciphertextLen = input.readInt()
                    if (ciphertextLen != CryptoConstants.AES_KEY_SIZE_BYTES) {
                        throw KeyUnavailableException("Invalid ciphertext length in wrapped key file: $ciphertextLen")
                    }
                    val ciphertext = ByteArray(ciphertextLen)
                    input.readFully(ciphertext)

                    val tagLen = input.readUnsignedShort()
                    if (tagLen != CryptoConstants.GCM_TAG_LENGTH_BYTES) {
                        throw KeyUnavailableException("Invalid auth tag length in wrapped key file: $tagLen")
                    }
                    val authTag = ByteArray(tagLen)
                    input.readFully(authTag)

                    if (input.available() > 0) {
                        throw KeyUnavailableException("Unexpected trailing bytes in wrapped key file")
                    }

                    return WrappedContentKeyRecord(
                        formatVersion = formatVersion,
                        keyId = keyId,
                        createdAt = createdAt,
                        nonce = nonce,
                        ciphertext = ciphertext,
                        authTag = authTag
                    )
                }
            } catch (e: CryptoException) {
                throw e
            } catch (e: Exception) {
                throw KeyUnavailableException("Corrupted wrapped content key file", e)
            }
        }
    }
}

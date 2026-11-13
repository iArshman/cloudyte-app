package com.cloudyte.app.core.security

import com.cloudyte.app.api.EncryptedMetadataEnvelope
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local plaintext metadata serialized and encrypted into [EncryptedMetadataEnvelope].
 *
 * Schema:
 * {
 *   "version": 1,
 *   "originalName": "example.pdf",
 *   "originalMimeType": "application/pdf",
 *   "originalExtension": "pdf",
 *   "folderId": "app_folder_id_or_null",
 *   "relativePath": "optional_relative_path_or_null",
 *   "plaintextSizeBytes": 123456,
 *   "plaintextChecksum": "sha256_of_plaintext",
 *   "createdAt": 1700000000000,
 *   "modifiedAt": 1700000000000
 * }
 */
data class PlaintextFileMetadata(
    val version: Int = CryptoConstants.METADATA_VERSION_V1,
    val originalName: String,
    val originalMimeType: String,
    val originalExtension: String,
    val folderId: String? = null,
    val relativePath: String? = null,
    val plaintextSizeBytes: Long,
    val plaintextChecksum: String,
    val createdAt: Long,
    val modifiedAt: Long
) {
    override fun toString(): String {
        return "PlaintextFileMetadata(version=$version, plaintextSizeBytes=$plaintextSizeBytes, fields=<REDACTED>)"
    }
}

/**
 * Encrypts and decrypts file metadata using AES-256-GCM with a metadata subkey derived from
 * CONTENT_KEY via HMAC-SHA256 domain separation (`"cloudyte-metadata-key-v1"`), a fresh 12-byte
 * random nonce per encryption, and AAD binding `(metadataVersion, algorithm, keyId)`.
 */
class EncryptedMetadataCodec(
    private val secureRandom: SecureRandom = SecureRandom()
) {

    @Throws(CryptoException::class)
    fun encryptMetadata(
        metadata: PlaintextFileMetadata,
        keyMaterial: ContentKeyMaterial
    ): EncryptedMetadataEnvelope {
        if (metadata.originalName.isBlank()) {
            throw MetadataDecryptionException("originalName must not be blank")
        }
        if (metadata.plaintextSizeBytes < 0L) {
            throw MetadataDecryptionException("plaintextSizeBytes must be non-negative")
        }

        val rawKey = keyMaterial.copyKeyBytes()
        val metadataSubKey = deriveMetadataSubKey(rawKey, keyMaterial.keyId)
        try {
            val plaintextJson = serializePlaintextMetadata(metadata).toByteArray(StandardCharsets.UTF_8)
            val nonce = ByteArray(CryptoConstants.GCM_NONCE_LENGTH_BYTES)
            secureRandom.nextBytes(nonce)

            val metadataVersion = CryptoConstants.METADATA_VERSION_V1
            val algorithm = CryptoConstants.METADATA_ALGORITHM_NAME
            val aad = buildMetadataAad(
                metadataVersion = metadataVersion,
                algorithm = algorithm,
                keyId = keyMaterial.keyId
            )

            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(metadataSubKey, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(aad)
            val ciphertextWithTag = cipher.doFinal(plaintextJson)

            return EncryptedMetadataEnvelope(
                metadataVersion = metadataVersion,
                algorithm = algorithm,
                keyId = keyMaterial.keyId,
                nonce = Base64UrlCodec.encode(nonce),
                ciphertext = Base64UrlCodec.encode(ciphertextWithTag)
            )
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw MetadataDecryptionException("Failed to encrypt file metadata", e)
        } finally {
            rawKey.fill(0)
            metadataSubKey.fill(0)
        }
    }

    @Throws(CryptoException::class)
    fun decryptMetadata(
        envelope: EncryptedMetadataEnvelope,
        keyMaterial: ContentKeyMaterial
    ): PlaintextFileMetadata {
        if (envelope.metadataVersion != CryptoConstants.METADATA_VERSION_V1) {
            throw UnsupportedEncryptionVersionException(
                envelope.metadataVersion,
                "Unsupported encrypted metadata version: ${envelope.metadataVersion}"
            )
        }
        if (envelope.algorithm != CryptoConstants.METADATA_ALGORITHM_NAME) {
            throw MetadataDecryptionException("Unsupported metadata encryption algorithm: ${envelope.algorithm}")
        }
        if (envelope.keyId.isBlank()) {
            throw MetadataDecryptionException("Encrypted metadata envelope is missing keyId")
        }
        if (envelope.keyId != keyMaterial.keyId) {
            throw WrongKeyIdException(expectedKeyId = envelope.keyId, actualKeyId = keyMaterial.keyId)
        }

        val nonce = try {
            Base64UrlCodec.decode(envelope.nonce)
        } catch (e: Exception) {
            throw MetadataDecryptionException("Invalid Base64 nonce in encrypted metadata", e)
        }
        if (nonce.size != CryptoConstants.GCM_NONCE_LENGTH_BYTES) {
            throw MetadataDecryptionException("Invalid metadata nonce length: ${nonce.size}")
        }

        val ciphertextWithTag = try {
            Base64UrlCodec.decode(envelope.ciphertext)
        } catch (e: Exception) {
            throw MetadataDecryptionException("Invalid Base64 ciphertext in encrypted metadata", e)
        }
        if (ciphertextWithTag.size <= CryptoConstants.GCM_TAG_LENGTH_BYTES) {
            throw MetadataDecryptionException("Encrypted metadata ciphertext is truncated")
        }

        val rawKey = keyMaterial.copyKeyBytes()
        val metadataSubKey = deriveMetadataSubKey(rawKey, keyMaterial.keyId)
        try {
            val aad = buildMetadataAad(
                metadataVersion = envelope.metadataVersion,
                algorithm = envelope.algorithm,
                keyId = envelope.keyId
            )
            val cipher = Cipher.getInstance(CryptoConstants.AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(metadataSubKey, CryptoConstants.AES_ALGORITHM),
                GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(aad)
            val decryptedBytes = cipher.doFinal(ciphertextWithTag)
            val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
            return deserializePlaintextMetadata(jsonString)
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw MetadataDecryptionException("Encrypted metadata failed GCM authentication or parsing", e)
        } finally {
            rawKey.fill(0)
            metadataSubKey.fill(0)
        }
    }

    fun serializeEnvelopeToJson(envelope: EncryptedMetadataEnvelope): String {
        return JSONObject().apply {
            put("metadataVersion", envelope.metadataVersion)
            put("algorithm", envelope.algorithm)
            put("keyId", envelope.keyId)
            put("nonce", envelope.nonce)
            put("ciphertext", envelope.ciphertext)
        }.toString()
    }

    @Throws(MetadataDecryptionException::class)
    fun deserializeEnvelopeFromJson(jsonText: String): EncryptedMetadataEnvelope {
        if (jsonText.isBlank()) {
            throw MetadataDecryptionException("Encrypted metadata JSON is empty")
        }
        return try {
            val json = JSONObject(jsonText)
            EncryptedMetadataEnvelope(
                metadataVersion = json.optInt("metadataVersion", -1),
                algorithm = json.optString("algorithm", ""),
                keyId = json.optString("keyId", ""),
                nonce = json.optString("nonce", ""),
                ciphertext = json.optString("ciphertext", "")
            )
        } catch (e: Exception) {
            throw MetadataDecryptionException("Malformed encrypted metadata envelope JSON", e)
        }
    }

    private fun serializePlaintextMetadata(metadata: PlaintextFileMetadata): String {
        return JSONObject().apply {
            put("version", metadata.version)
            put("originalName", metadata.originalName)
            put("originalMimeType", metadata.originalMimeType)
            put("originalExtension", metadata.originalExtension)
            if (metadata.folderId != null) {
                put("folderId", metadata.folderId)
            } else {
                put("folderId", JSONObject.NULL)
            }
            if (metadata.relativePath != null) {
                put("relativePath", metadata.relativePath)
            } else {
                put("relativePath", JSONObject.NULL)
            }
            put("plaintextSizeBytes", metadata.plaintextSizeBytes)
            put("plaintextChecksum", metadata.plaintextChecksum)
            put("createdAt", metadata.createdAt)
            put("modifiedAt", metadata.modifiedAt)
        }.toString()
    }

    private fun deserializePlaintextMetadata(jsonText: String): PlaintextFileMetadata {
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw MetadataDecryptionException("Decrypted metadata is not valid JSON", e)
        }

        val version = json.optInt("version", -1)
        if (version != CryptoConstants.METADATA_VERSION_V1) {
            throw UnsupportedEncryptionVersionException(version, "Unsupported decrypted metadata schema version: $version")
        }

        val originalName = json.optString("originalName", "")
        if (originalName.isBlank()) {
            throw MetadataDecryptionException("Decrypted metadata is missing originalName")
        }

        val originalMimeType = json.optString("originalMimeType", CryptoConstants.ENCRYPTED_MIME_TYPE)
            .ifBlank { CryptoConstants.ENCRYPTED_MIME_TYPE }
        val originalExtension = json.optString("originalExtension", "")
        val folderId = if (json.isNull("folderId")) null else json.optString("folderId", "").takeIf { it.isNotEmpty() }
        val relativePath = if (json.isNull("relativePath")) null else json.optString("relativePath", "").takeIf { it.isNotEmpty() }

        if (!json.has("plaintextSizeBytes")) {
            throw MetadataDecryptionException("Decrypted metadata is missing plaintextSizeBytes")
        }
        val plaintextSizeBytes = json.optLong("plaintextSizeBytes", -1L)
        if (plaintextSizeBytes < 0L) {
            throw MetadataDecryptionException("Decrypted metadata has negative plaintextSizeBytes")
        }

        val plaintextChecksum = json.optString("plaintextChecksum", "")
        val createdAt = json.optLong("createdAt", 0L)
        val modifiedAt = json.optLong("modifiedAt", createdAt)

        return PlaintextFileMetadata(
            version = version,
            originalName = originalName,
            originalMimeType = originalMimeType,
            originalExtension = originalExtension,
            folderId = folderId,
            relativePath = relativePath,
            plaintextSizeBytes = plaintextSizeBytes,
            plaintextChecksum = plaintextChecksum,
            createdAt = createdAt,
            modifiedAt = modifiedAt
        )
    }

    companion object {
        private val DOMAIN_METADATA = "cloudyte-metadata-key-v1".toByteArray(StandardCharsets.UTF_8)

        internal fun deriveMetadataSubKey(masterContentKey: ByteArray, keyId: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(masterContentKey, "HmacSHA256"))
            mac.update(DOMAIN_METADATA)
            mac.update(0.toByte())
            mac.update(keyId.toByteArray(StandardCharsets.UTF_8))
            return mac.doFinal()
        }

        internal fun buildMetadataAad(
            metadataVersion: Int,
            algorithm: String,
            keyId: String
        ): ByteArray {
            val algBytes = algorithm.toByteArray(StandardCharsets.UTF_8)
            val keyIdBytes = keyId.toByteArray(StandardCharsets.UTF_8)
            return ByteBuffer.allocate(4 + 2 + algBytes.size + 2 + keyIdBytes.size)
                .putInt(metadataVersion)
                .putShort(algBytes.size.toShort())
                .put(algBytes)
                .putShort(keyIdBytes.size.toShort())
                .put(keyIdBytes)
                .array()
        }
    }
}

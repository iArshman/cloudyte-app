package com.cloudyte.app.core.security

import java.nio.charset.StandardCharsets
import java.util.Base64

object CryptoConstants {
    const val MAGIC_STRING = "CYTE"
    val MAGIC_BYTES: ByteArray = MAGIC_STRING.toByteArray(StandardCharsets.US_ASCII)

    const val FORMAT_VERSION_V1: Int = 1
    const val KEY_FORMAT_VERSION_V1: Int = 1
    const val METADATA_VERSION_V1: Int = 1
    const val BACKUP_VERSION_V1: Int = 1

    const val AES_ALGORITHM = "AES"
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    const val METADATA_ALGORITHM_NAME = "AES-256-GCM"

    const val AES_KEY_SIZE_BITS = 256
    const val AES_KEY_SIZE_BYTES = 32
    const val GCM_NONCE_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val GCM_TAG_LENGTH_BYTES = 16

    const val FILE_SALT_LENGTH_BYTES = 8
    const val DEFAULT_CHUNK_SIZE_BYTES = 1024 * 1024 // 1 MiB (1,048,576 bytes)
    const val MIN_CHUNK_SIZE_BYTES = 1024 // 1 KiB minimum for test flexibility
    const val MAX_CHUNK_SIZE_BYTES = 16 * 1024 * 1024 // 16 MiB maximum sanity bound
    const val STREAM_BUFFER_SIZE_BYTES = 64 * 1024 // 64 KiB I/O read/write buffer

    const val HEADER_RESERVED_LENGTH_BYTES = 16
    const val MAX_KEY_ID_LENGTH_BYTES = 256

    const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_HEADER_NAME = "PBKDF2-HMAC-SHA256"
    const val PBKDF2_ITERATIONS = 310_000
    const val PBKDF2_SALT_LENGTH_BYTES = 16

    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val MASTER_KEY_ALIAS = "cloudyte_master_key_v1"

    const val WRAPPED_KEY_DIR = "crypto"
    const val WRAPPED_KEY_FILE = "content_key.bin"
    const val KEY_BACKUP_STATUS_FILE = "key_backup_state.json"

    const val ENCRYPTED_STAGING_DIR = "encrypted_staging"
    const val DOWNLOAD_STAGING_DIR = "download_staging"
    const val DECRYPTED_VIEW_CACHE_DIR = "decrypted_view_cache"

    const val OPAQUE_FILE_PREFIX = "cyte_"
    const val OPAQUE_FILE_SUFFIX = ".bin"
    const val ENCRYPTED_MIME_TYPE = "application/octet-stream"
    const val ENCRYPTED_FALLBACK_DISPLAY_NAME = "Encrypted file"
}

/**
 * URL-safe Base64 without padding helper that works on both Android runtime and JVM unit tests.
 */
object Base64UrlCodec {
    fun encode(bytes: ByteArray): String {
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }
    }

    fun decode(encoded: String): ByteArray {
        if (encoded.isBlank()) throw IllegalArgumentException("Base64 string cannot be blank")
        return try {
            Base64.getUrlDecoder().decode(encoded.trim())
        } catch (_: Throwable) {
            android.util.Base64.decode(
                encoded.trim(),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }
    }
}

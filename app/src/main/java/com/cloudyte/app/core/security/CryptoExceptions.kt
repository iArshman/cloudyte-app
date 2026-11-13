package com.cloudyte.app.core.security

import java.io.IOException

open class CryptoException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class KeyUnavailableException(
    message: String = "Encryption key is unavailable. Restore from recovery backup to access encrypted files.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class KeyUnwrapException(
    message: String = "Failed to unwrap encryption key with Android Keystore master key.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class KeyBackupException(
    message: String = "Invalid or corrupted recovery key backup.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class InvalidPassphraseException(
    message: String = "Wrong recovery passphrase or corrupted key backup.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class CorruptedCiphertextException(
    message: String = "Encrypted file is corrupted or failed authentication.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class InvalidEncryptedHeaderException(
    message: String = "Invalid or malformed encrypted file header.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class UnsupportedEncryptionVersionException(
    val version: Int,
    message: String = "Unsupported encryption format version: $version"
) : CryptoException(message)

class FrameAuthenticationException(
    val frameIndex: Int,
    message: String = "Authentication failed at encrypted frame $frameIndex.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class WrongKeyIdException(
    val expectedKeyId: String?,
    val actualKeyId: String?,
    message: String = "Encryption key ID mismatch (expected=$expectedKeyId, actual=$actualKeyId)."
) : CryptoException(message)

class MetadataDecryptionException(
    message: String = "Failed to authenticate or decrypt file metadata.",
    cause: Throwable? = null
) : CryptoException(message, cause)

class EncryptedSharingNotSupportedException(
    message: String = "Encrypted sharing is not supported"
) : CryptoException(message)

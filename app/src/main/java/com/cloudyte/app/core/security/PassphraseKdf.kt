package com.cloudyte.app.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password-based key derivation using PBKDF2-HMAC-SHA256 with 310,000 iterations,
 * 16-byte random salt, and 256-bit derived key output.
 */
object PassphraseKdf {

    fun generateSalt(secureRandom: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(CryptoConstants.PBKDF2_SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    @Throws(CryptoException::class)
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = CryptoConstants.PBKDF2_ITERATIONS,
        keyLengthBits: Int = CryptoConstants.AES_KEY_SIZE_BITS
    ): ByteArray {
        if (passphrase.isEmpty()) {
            throw InvalidPassphraseException("Recovery passphrase must not be empty")
        }
        if (salt.size != CryptoConstants.PBKDF2_SALT_LENGTH_BYTES) {
            throw KeyBackupException("Invalid KDF salt length: ${salt.size}")
        }
        if (iterations < CryptoConstants.PBKDF2_ITERATIONS) {
            throw KeyBackupException("KDF iteration count ($iterations) is below minimum required (${CryptoConstants.PBKDF2_ITERATIONS})")
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, keyLengthBits)
        return try {
            val factory = SecretKeyFactory.getInstance(CryptoConstants.PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } catch (e: Exception) {
            throw CryptoException("Failed to derive key from passphrase", e)
        } finally {
            spec.clearPassword()
        }
    }
}

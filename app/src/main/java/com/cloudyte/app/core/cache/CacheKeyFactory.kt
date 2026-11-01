package com.cloudyte.app.core.cache

import com.cloudyte.app.api.CloudFile
import java.security.MessageDigest

object CacheKeyFactory {
    fun thumbnail(
        accountId: String,
        providerType: String,
        file: CloudFile,
        sizePx: Int
    ): String {
        val revision = file.updatedAt?.toString()
            ?: file.time?.toString()
            ?: file.sizeBytes.toString()
        return "thumb:v1:${digest(accountId)}:${digest(providerType)}:${digest(file.effectiveId)}:$revision:${sizePx.coerceIn(64, 512)}"
    }

    fun preview(accountId: String, providerType: String, remoteId: String, revision: String?): String {
        return "preview:v1:${digest(accountId)}:${digest(providerType)}:${digest(remoteId)}:${digest(revision ?: "unknown")}"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}

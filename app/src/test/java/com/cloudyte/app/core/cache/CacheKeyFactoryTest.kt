package com.cloudyte.app.core.cache

import com.cloudyte.app.api.CloudFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CacheKeyFactoryTest {
    private val file = CloudFile(id = "remote-1", name = "photo.jpg", type = "image", updatedAt = 42L)

    @Test
    fun sameInputsProduceStableKey() {
        assertEquals(
            CacheKeyFactory.thumbnail("account-1", "Google Drive", file, 256),
            CacheKeyFactory.thumbnail("account-1", "Google Drive", file, 256)
        )
    }

    @Test
    fun accountProviderRevisionAndSizeAreIsolated() {
        val base = CacheKeyFactory.thumbnail("account-1", "Google Drive", file, 256)
        assertNotEquals(base, CacheKeyFactory.thumbnail("account-2", "Google Drive", file, 256))
        assertNotEquals(base, CacheKeyFactory.thumbnail("account-1", "OneDrive", file, 256))
        assertNotEquals(base, CacheKeyFactory.thumbnail("account-1", "Google Drive", file.copy(updatedAt = 43L), 256))
        assertNotEquals(base, CacheKeyFactory.thumbnail("account-1", "Google Drive", file, 128))
    }
}

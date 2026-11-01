package com.cloudyte.app.data.providers

import com.cloudyte.app.api.CloudyteApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ProviderRegistryTest {
    private fun registry(): ProviderRegistry {
        val api = Proxy.newProxyInstance(
            CloudyteApi::class.java.classLoader,
            arrayOf(CloudyteApi::class.java)
        ) { _, _, _ -> null } as CloudyteApi
        return ProviderRegistry(api)
    }

    @Test
    fun `provider aliases resolve to exact adapters`() {
        val registry = registry()
        assertEquals("telegram", registry.forAccountType("TG Drive").type)
        assertEquals("google_drive", registry.forAccountType("Google").type)
        assertEquals("onedrive", registry.forAccountType("OneDrive").type)
    }

    @Test
    fun `unsupported provider names never fall through to another adapter`() {
        val error = try {
            registry().forAccountType("mystery-cloud")
            throw AssertionError("expected unsupported provider")
        } catch (expected: UnsupportedProviderOperation) {
            expected
        }
        assertEquals("mystery_cloud", error.providerType)
    }

    @Test
    fun `native resumability and cancellation are not confused with server staging`() {
        val registry = registry()
        assertTrue(registry.forAccountType("google").capabilities.supportsResumableUpload)
        assertTrue(registry.forAccountType("google").capabilities.supportsProviderNativeResumability)
        assertTrue(registry.forAccountType("onedrive").capabilities.supportsProviderNativeResumability)
        assertTrue(registry.forAccountType("onedrive").capabilities.supportsProviderSessionCancellation)
        assertTrue(registry.forAccountType("telegram").capabilities.supportsResumableUpload)
        assertFalse(registry.forAccountType("telegram").capabilities.supportsProviderNativeResumability)
        assertFalse(registry.forAccountType("telegram").capabilities.supportsProviderSessionCancellation)
        assertFalse(registry.forAccountType("cloudyte").capabilities.supportsResumableUpload)
    }
}

package com.mystx.app.provider

import com.mystx.app.manager.KeyManager
import com.mystx.app.model.OpenCodeModels
import com.mystx.app.model.PrefKeys
import com.mystx.app.model.ProviderType
import com.mystx.app.model.SarvamModels
import com.mystx.app.service.discovery.ModelDiscoveryService
import org.junit.Assert.*
import org.junit.Test

/** Regression unit tests for Sarvam AI v2, OpenCode Zen, and host-scoped Custom key isolation. */
class SarvamAndOpenCodeProviderTest {

    @Test
    fun providers_route_sarvam_and_opencode() {
        assertSame(SarvamConfig, Providers.forType(ProviderType.SARVAM))
        assertSame(OpenCodeConfig, Providers.forType(ProviderType.OPENCODE))
        assertEquals("https://api.sarvam.ai/v2", SarvamConfig.ENDPOINT)
        assertEquals("https://opencode.ai/zen/v1", OpenCodeConfig.ENDPOINT)
        assertEquals(PrefKeys.SARVAM_MODEL, SarvamConfig.modelPrefKey)
        assertEquals(PrefKeys.OPENCODE_MODEL, OpenCodeConfig.modelPrefKey)
        assertEquals(SarvamModels.DEFAULT, SarvamConfig.sanitizeModel(""))
        assertEquals(OpenCodeModels.DEFAULT, OpenCodeConfig.sanitizeModel(""))
    }

    @Test
    fun discovery_registers_sarvam_and_opencode() {
        assertNotNull(ModelDiscoveryService.getDiscovery(ProviderType.SARVAM))
        assertNotNull(ModelDiscoveryService.getDiscovery(ProviderType.OPENCODE))
        assertEquals("cached_models_sarvam", ModelDiscoveryService.getCacheKey(ProviderType.SARVAM))
        assertEquals("cached_models_opencode", ModelDiscoveryService.getCacheKey(ProviderType.OPENCODE))
    }

    @Test
    fun custom_provider_tags_isolate_hosts_cleanly() {
        val openRouterTag = KeyManager.buildProviderTag(ProviderType.CUSTOM, "https://openrouter.ai/api/v1")
        val tokenRouterTag = KeyManager.buildProviderTag(ProviderType.CUSTOM, "https://api.tokenrouter.io/v1")

        assertEquals("custom:openrouter.ai", openRouterTag)
        assertEquals("custom:api.tokenrouter.io", tokenRouterTag)
        assertNotEquals(openRouterTag, tokenRouterTag)
        assertEquals("openrouter.ai", KeyManager.customHostFromTag(openRouterTag))
        assertEquals("api.tokenrouter.io", KeyManager.customHostFromTag(tokenRouterTag))
        assertEquals(ProviderType.CUSTOM, KeyManager.baseProviderFromTag(openRouterTag))
    }
}

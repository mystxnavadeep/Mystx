package com.mystx.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mystx.app.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class KeyHealth(
    val isReady: Boolean,
    val cooldownSeconds: Long = 0L,
    val isInvalid: Boolean = false
)

class KeyManager internal constructor(
    context: Context,
    private val cipher: KeyCipher
) {
    internal constructor(context: Context) : this(context, AndroidKeystoreCipher())

    private val prefs: SharedPreferences = context.getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "KeyManager"
        private const val PREF_KEY_ARRAY = "keys_array"
        private const val PREF_KEY_PROVIDERS = "key_providers_map"
        private const val CACHE_TTL_MS = 5_000L
        private const val MAX_KEY_LENGTH = 256
        private const val INVALID_KEY_TTL_MS = 900_000L // 15 min

        internal fun isLegacyPlaintext(stored: String): Boolean = stored.trimStart().startsWith("[")

        /** Extracts clean lowercase host from a custom base URL (e.g. "https://openrouter.ai/api/v1" -> "openrouter.ai"). */
        fun extractHostLabel(endpoint: String?): String? {
            val raw = endpoint?.trim().orEmpty()
            if (raw.isBlank()) return null
            return try {
                val host = URI(raw).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
                host?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /** Builds a host-scoped tag for Custom endpoints (e.g. "custom:openrouter.ai"). */
        fun buildProviderTag(providerType: String, customEndpoint: String = ""): String {
            val base = ProviderType.sanitize(providerType)
            if (base == ProviderType.CUSTOM) {
                val host = extractHostLabel(customEndpoint)
                if (!host.isNullOrBlank()) return "${ProviderType.CUSTOM}:$host"
            }
            return base
        }

        fun baseProviderFromTag(tag: String): String {
            val clean = tag.trim().lowercase(Locale.ROOT)
            return if (clean.startsWith("${ProviderType.CUSTOM}:")) {
                ProviderType.CUSTOM
            } else {
                ProviderType.sanitize(clean)
            }
        }

        fun customHostFromTag(tag: String): String? {
            val clean = tag.trim().lowercase(Locale.ROOT)
            return if (clean.startsWith("${ProviderType.CUSTOM}:")) {
                clean.removePrefix("${ProviderType.CUSTOM}:").takeIf { it.isNotBlank() }
            } else null
        }

        fun detectKnownPrefixProvider(key: String): String? {
            val k = key.trim()
            return when {
                k.startsWith("AIza") -> ProviderType.GEMINI
                k.startsWith("sk-or-") -> "${ProviderType.CUSTOM}:openrouter.ai"
                else -> null
            }
        }

        fun detectProviderType(key: String, fallback: String = ProviderType.GEMINI): String {
            val detected = detectKnownPrefixProvider(key)
            return if (detected != null) baseProviderFromTag(detected) else ProviderType.sanitize(fallback)
        }
    }

    private val rateLimitedKeys = ConcurrentHashMap<String, Long>()
    private val invalidKeys = ConcurrentHashMap<String, Long>()
    private val roundRobinIndex = AtomicInteger(0)
    private val providerRoundRobin = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var cachedKeys: List<String>? = null
    @Volatile
    private var cacheTimestamp = 0L
    @Volatile
    private var cachedCipherText: String? = null

    val keystoreAvailable: Boolean get() = cipher.available

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun keyTagId(key: String): String {
        val k = key.trim()
        return "${k.hashCode()}_${k.length}_${k.takeLast(4)}"
    }

    private fun loadProviderMap(): MutableMap<String, String> {
        val raw = prefs.getString(PREF_KEY_PROVIDERS, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.optString(k, ProviderType.GEMINI).trim().lowercase(Locale.ROOT)
                if (v.isNotBlank()) map[k] = v
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveProviderMap(map: Map<String, String>) {
        try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit().putString(PREF_KEY_PROVIDERS, obj.toString()).apply()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun getKeys(): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedKeys
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val stored = prefs.getString(PREF_KEY_ARRAY, null) ?: return emptyList()
        if (cached != null && stored == cachedCipherText) {
            cacheTimestamp = now
            return cached
        }
        if (isLegacyPlaintext(stored)) {
            return try {
                val encrypted = cipher.encrypt(stored)
                prefs.edit().putString(PREF_KEY_ARRAY, encrypted).commit()
                val list = JSONArray(stored).toStringList()
                cacheKeys(list, encrypted)
                list
            } catch (_: Exception) {
                try { JSONArray(stored).toStringList() } catch (_: Exception) { emptyList() }
            }
        }
        val jsonStr = cipher.decrypt(stored) ?: return emptyList()
        val list = try { JSONArray(jsonStr).toStringList() } catch (_: Exception) { emptyList() }
        cacheKeys(list, stored)
        return list
    }

    private fun cacheKeys(keys: List<String>, cipherText: String?) {
        cachedKeys = keys
        cachedCipherText = cipherText
        cacheTimestamp = System.currentTimeMillis()
    }

    @Synchronized
    private fun saveKeys(keys: List<String>): Boolean {
        val arr = JSONArray(keys)
        return try {
            val cipherText = cipher.encrypt(arr.toString())
            prefs.edit().putString(PREF_KEY_ARRAY, cipherText).apply()
            cacheKeys(keys, cipherText)
            true
        } catch (_: Exception) {
            cachedKeys = null
            cachedCipherText = null
            cacheTimestamp = 0L
            false
        }
    }

    @Synchronized
    fun addKey(key: String, providerType: String? = null, customEndpoint: String = ""): Boolean {
        val trimmedKey = key.trim()
        if (trimmedKey.isBlank() || trimmedKey.length > MAX_KEY_LENGTH) return false
        val stored = prefs.getString(PREF_KEY_ARRAY, null)
        if (!stored.isNullOrEmpty() && !isLegacyPlaintext(stored) && cipher.decrypt(stored) == null) {
            Log.e(TAG, "Refusing to overwrite undecryptable key store")
            return false
        }
        val keys = getKeys().toMutableList()
        if (!keys.contains(trimmedKey)) {
            keys.add(trimmedKey)
            if (!saveKeys(keys)) return false
        }
        val resolvedTag = if (providerType != null) {
            buildProviderTag(providerType, customEndpoint)
        } else {
            detectKnownPrefixProvider(trimmedKey)
        }
        if (resolvedTag != null) {
            val map = loadProviderMap()
            map[keyTagId(trimmedKey)] = resolvedTag
            saveProviderMap(map)
        }
        invalidKeys.remove(trimmedKey)
        rateLimitedKeys.remove(trimmedKey)
        return true
    }

    @Synchronized
    fun removeKey(key: String): Boolean {
        val trimmedKey = key.trim()
        val keys = getKeys().toMutableList()
        keys.remove(trimmedKey)
        val saved = saveKeys(keys)
        val map = loadProviderMap()
        if (map.remove(keyTagId(trimmedKey)) != null) {
            saveProviderMap(map)
        }
        rateLimitedKeys.remove(trimmedKey)
        invalidKeys.remove(trimmedKey)
        return saved
    }

    /** Returns raw stored tag, e.g. "gemini", "sarvam", "opencode", or "custom:openrouter.ai". */
    @Synchronized
    fun getProviderTagForKey(key: String, defaultProvider: String = ProviderType.GEMINI): String {
        val map = loadProviderMap()
        val explicit = map[keyTagId(key)]
        if (explicit != null) return explicit
        return detectKnownPrefixProvider(key) ?: ProviderType.sanitize(defaultProvider)
    }

    @Synchronized
    fun getProviderForKey(key: String, defaultProvider: String = ProviderType.GEMINI): String =
        baseProviderFromTag(getProviderTagForKey(key, defaultProvider))

    /** Human-readable provider badge, showing exact host for Custom keys (e.g. "Custom · openrouter.ai"). */
    @Synchronized
    fun getKeyDisplayLabel(key: String, defaultProvider: String = ProviderType.GEMINI): String {
        val tag = getProviderTagForKey(key, defaultProvider)
        val host = customHostFromTag(tag)
        if (host != null) return "Custom · $host"
        return when (baseProviderFromTag(tag)) {
            ProviderType.GEMINI -> "Gemini"
            ProviderType.SARVAM -> "Sarvam AI"
            ProviderType.OPENCODE -> "OpenCode"
            ProviderType.BAI -> "B.ai"
            ProviderType.GROQ -> "Sarvam AI"
            else -> "Custom"
        }
    }

    /**
     * Strictly returns keys matching [providerType] — and for Custom endpoints with a host,
     * isolates keys per host (so OpenRouter never receives a TokenRouter key or vice versa).
     */
    @Synchronized
    fun getKeysForProvider(
        providerType: String,
        defaultProvider: String = ProviderType.GEMINI,
        customEndpoint: String = ""
    ): List<String> {
        val target = ProviderType.sanitize(providerType)
        val all = getKeys()
        if (target == ProviderType.CUSTOM) {
            val targetHost = extractHostLabel(customEndpoint)
            if (!targetHost.isNullOrBlank()) {
                val exactTag = "${ProviderType.CUSTOM}:$targetHost"
                val exactMatches = all.filter { getProviderTagForKey(it, defaultProvider) == exactTag }
                if (exactMatches.isNotEmpty()) return exactMatches
                // Fall back ONLY to legacy untagged "custom" keys — never to a different host's keys!
                return all.filter { getProviderTagForKey(it, defaultProvider) == ProviderType.CUSTOM }
            }
            return all.filter { getProviderForKey(it, defaultProvider) == ProviderType.CUSTOM }
        }
        return all.filter { getProviderForKey(it, defaultProvider) == target }
    }

    @Synchronized
    fun getNextKeyForProvider(
        providerType: String,
        alreadyTried: Set<String> = emptySet(),
        defaultProvider: String = ProviderType.GEMINI,
        customEndpoint: String = ""
    ): String? {
        val keys = getKeysForProvider(providerType, defaultProvider, customEndpoint)
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (key in alreadyTried) return@filter false
            if (isInvalid(key)) return@filter false
            val limitTime = rateLimitedKeys[key] ?: 0L
            now > limitTime
        }
        if (validKeys.isEmpty()) return null

        val bucket = buildProviderTag(providerType, customEndpoint)
        val counter = providerRoundRobin.getOrPut(bucket) { AtomicInteger(0) }
        val idx = (counter.getAndIncrement() and Int.MAX_VALUE) % validKeys.size
        return validKeys[idx]
    }

    @Synchronized
    fun getNextKey(alreadyTried: Set<String> = emptySet()): String? {
        val keys = getKeys()
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (key in alreadyTried) return@filter false
            if (isInvalid(key)) return@filter false
            val limitTime = rateLimitedKeys[key] ?: 0L
            now > limitTime
        }

        if (validKeys.isEmpty()) return null

        val idx = (roundRobinIndex.getAndIncrement() and Int.MAX_VALUE) % validKeys.size
        return validKeys[idx]
    }

    fun getKeyHealth(key: String): KeyHealth {
        val k = key.trim()
        if (isInvalid(k)) return KeyHealth(isReady = false, isInvalid = true)
        val limitTime = rateLimitedKeys[k] ?: 0L
        val remainingMs = limitTime - System.currentTimeMillis()
        return if (remainingMs > 0L) {
            KeyHealth(isReady = false, cooldownSeconds = (remainingMs + 999L) / 1000L)
        } else {
            KeyHealth(isReady = true)
        }
    }

    @Synchronized
    fun getAvailableKeyCount(
        providerType: String,
        defaultProvider: String = ProviderType.GEMINI,
        customEndpoint: String = ""
    ): Int = getKeysForProvider(providerType, defaultProvider, customEndpoint).count { getKeyHealth(it).isReady }

    fun reportRateLimit(key: String, retryAfterSeconds: Long = 60) {
        val trimmedKey = key.trim()
        val cooldown = retryAfterSeconds.coerceIn(1, 600)
        rateLimitedKeys[trimmedKey] = System.currentTimeMillis() + cooldown * 1_000
    }

    fun markInvalid(key: String) {
        invalidKeys[key.trim()] = System.currentTimeMillis() + INVALID_KEY_TTL_MS
    }

    @Synchronized
    fun clearMarks(key: String) {
        val trimmedKey = key.trim()
        invalidKeys.remove(trimmedKey)
        rateLimitedKeys.remove(trimmedKey)
    }

    private fun isInvalid(key: String): Boolean {
        val until = invalidKeys[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            invalidKeys.remove(key)
            return false
        }
        return true
    }

    fun getShortestWaitTimeMs(): Long? {
        val keys = getKeys()
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val waits = keys.filter { !isInvalid(it) }
            .mapNotNull { key ->
                val limitTime = rateLimitedKeys[key] ?: return@mapNotNull null
                val remaining = limitTime - now
                if (remaining > 0) remaining else null
            }
        return waits.minOrNull()
    }
}

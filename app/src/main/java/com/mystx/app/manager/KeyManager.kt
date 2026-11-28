package com.mystx.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mystx.app.model.ProviderType
import org.json.JSONArray
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
        private const val CACHE_TTL_MS = 5_000L
        private const val MAX_KEY_LENGTH = 256
        private const val INVALID_KEY_TTL_MS = 900_000L // 15 min

        internal fun isLegacyPlaintext(stored: String): Boolean = stored.trimStart().startsWith("[")

        /** Auto-detects provider from key prefix so Gemini and Groq keys never collide. */
        fun detectProviderType(key: String): String {
            val k = key.trim()
            return when {
                k.startsWith("AIza") -> ProviderType.GEMINI
                k.startsWith("gsk_") -> ProviderType.GROQ
                else -> ProviderType.CUSTOM
            }
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
    fun addKey(key: String): Boolean {
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
        rateLimitedKeys.remove(trimmedKey)
        invalidKeys.remove(trimmedKey)
        return saved
    }

    /** Returns keys belonging to [providerType] (Gemini AIza..., Groq gsk_..., or Custom/B.ai). */
    @Synchronized
    fun getKeysForProvider(providerType: String): List<String> {
        val all = getKeys()
        return when (providerType) {
            ProviderType.GEMINI -> all.filter { detectProviderType(it) == ProviderType.GEMINI }
            ProviderType.GROQ -> all.filter { detectProviderType(it) == ProviderType.GROQ }
            ProviderType.BAI, ProviderType.CUSTOM -> all.filter {
                val p = detectProviderType(it)
                p != ProviderType.GEMINI && p != ProviderType.GROQ
            }
            else -> all
        }
    }

    /**
     * Per-provider round-robin load balancer: divides requests evenly across all healthy
     * keys for [providerType] (e.g. Gemini Key 1 -> Key 2 -> Key 3), skipping rate-limited,
     * invalid, and already-tried keys so failures automatically rotate to the next key.
     */
    @Synchronized
    fun getNextKeyForProvider(providerType: String, alreadyTried: Set<String> = emptySet()): String? {
        val keys = getKeysForProvider(providerType)
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (key in alreadyTried) return@filter false
            if (isInvalid(key)) return@filter false
            val limitTime = rateLimitedKeys[key] ?: 0L
            now > limitTime
        }
        if (validKeys.isEmpty()) return null

        val counter = providerRoundRobin.getOrPut(providerType) { AtomicInteger(0) }
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
    fun getAvailableKeyCount(providerType: String): Int =
        getKeysForProvider(providerType).count { getKeyHealth(it).isReady }

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

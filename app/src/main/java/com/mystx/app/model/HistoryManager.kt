package com.mystx.app.model

import android.content.Context
import com.mystx.app.manager.AndroidKeystoreCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HistoryManager {
    private const val MAX_HISTORY = 100
    private const val FILE_NAME = "history.json"

    data class HistoryItem(
        val originalText: String,
        val commandTrigger: String,
        val response: String,
        val timestamp: Long,
        val isSelection: Boolean
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("originalText", originalText)
                put("commandTrigger", commandTrigger)
                put("response", response)
                put("timestamp", timestamp)
                put("isSelection", isSelection)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): HistoryItem {
                return HistoryItem(
                    originalText = json.optString("originalText", ""),
                    commandTrigger = json.optString("commandTrigger", ""),
                    response = json.optString("response", ""),
                    timestamp = json.optLong("timestamp", 0L),
                    isSelection = json.optBoolean("isSelection", false)
                )
            }
        }
    }

    private val cipher by lazy { AndroidKeystoreCipher() }
    private var cachedHistory = mutableListOf<HistoryItem>()
    private var isLoaded = false

    @Synchronized
    private fun loadHistory(context: Context) {
        if (isLoaded) return
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            isLoaded = true
            return
        }
        try {
            val raw = file.readText()
            val isPlaintext = raw.trimStart().startsWith("[")
            val content = if (isPlaintext) {
                raw
            } else {
                cipher.decrypt(raw) ?: return
            }
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                cachedHistory.add(HistoryItem.fromJson(array.getJSONObject(i)))
            }
            // Sort descending by timestamp
            cachedHistory.sortByDescending { it.timestamp }
            isLoaded = true
            if (isPlaintext && cipher.available) {
                saveHistory(context)
            }
        } catch (_: Exception) {
            isLoaded = true
        }
    }

    @Synchronized
    private fun saveHistory(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            val array = JSONArray()
            cachedHistory.forEach { array.put(it.toJson()) }
            val encrypted = cipher.encrypt(array.toString())
            tmpFile.writeText(encrypted)
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } catch (_: Exception) {
            try { tmpFile.delete() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun addEntry(context: Context, originalText: String, commandTrigger: String, response: String, isSelection: Boolean) {
        loadHistory(context)
        if (!isLoaded) return
        val newItem = HistoryItem(originalText, commandTrigger, response, System.currentTimeMillis(), isSelection)
        // Remove exact duplicate if exists
        cachedHistory.removeAll { it.originalText == originalText && it.commandTrigger == commandTrigger && it.isSelection == isSelection }
        cachedHistory.add(0, newItem)
        if (cachedHistory.size > MAX_HISTORY) {
            cachedHistory = cachedHistory.take(MAX_HISTORY).toMutableList()
        }
        saveHistory(context)
    }

    @Synchronized
    fun getHistory(context: Context): List<HistoryItem> {
        loadHistory(context)
        return cachedHistory.toList()
    }

    @Synchronized
    fun findCachedResponse(
        context: Context,
        originalText: String,
        commandTrigger: String,
        isSelection: Boolean = false
    ): String? {
        loadHistory(context)
        return cachedHistory.find {
            it.originalText == originalText &&
                it.commandTrigger == commandTrigger &&
                it.isSelection == isSelection
        }?.response
    }
}

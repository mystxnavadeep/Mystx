package com.mystx.app.api

import com.mystx.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val MAX_RESPONSE_CHARS = 256 * 1024
    private const val ALLOWED_RELEASE_URL_PREFIX = "https://github.com/mystxnavadeep/Mystx/releases/"
    private val SEMVER_REGEX = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

    data class UpdateInfo(val version: String, val url: String, val body: String)

    suspend fun checkForUpdates(currentVersionName: String): UpdateInfo? {
        if (!BuildConfig.ENABLE_UPDATER) return null
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/mystxnavadeep/Mystx/releases/latest")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mystx-App")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (connection.responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            if (sb.length + n > MAX_RESPONSE_CHARS) return@withContext null
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }

                    val json = JSONObject(responseBody)
                    val tagName = json.getString("tag_name")
                    val htmlUrl = json.getString("html_url")
                    val releaseNotes = json.optString("body", "")

                    if (!htmlUrl.startsWith(ALLOWED_RELEASE_URL_PREFIX)) {
                        return@withContext null
                    }

                    val latestClean = tagName.removePrefix("v")
                    val currentClean = currentVersionName.removePrefix("v")

                    if (isNewerVersion(latestClean, currentClean)) {
                        return@withContext UpdateInfo(tagName, htmlUrl, releaseNotes)
                    }
                }
            } catch (_: Exception) {
                // ignore network errors
            } finally {
                connection?.disconnect()
            }
            null
        }
    }

    private fun parseVersion(version: String): Triple<Int, Int, Int>? {
        val match = SEMVER_REGEX.find(version.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return Triple(major, minor, patch)
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = parseVersion(latest) ?: return false
        val c = parseVersion(current) ?: return false
        if (l.first != c.first) return l.first > c.first
        if (l.second != c.second) return l.second > c.second
        return l.third > c.third
    }
}

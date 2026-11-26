package com.mystx.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mystx.app.BuildConfig
import com.mystx.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private sealed class FetchResult {
        data class Success(val release: JSONObject) : FetchResult()
        object Retry : FetchResult()
        object Skip : FetchResult()
    }

    companion object {
        const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 9001
        private const val MAX_RESPONSE_CHARS = 1_048_576
        private const val PREFS_NAME = "update_check"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
        private val SEMVER_REGEX = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/mystxnavadeep/Mystx/releases/latest"
        private const val RELEASES_URL =
            "https://github.com/mystxnavadeep/Mystx/releases/latest"

        private fun parseVersion(version: String): Triple<Int, Int, Int>? {
            val match = SEMVER_REGEX.find(version.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: 0
            return Triple(major, minor, patch)
        }

        /**
         * Compares dot-separated versions (e.g. "1.0.50" > "1.0.49").
         * Returns true if [latest] is strictly newer than [current].
         *
         * Internal (rather than a private instance method) so it can be unit tested without
         * constructing a Worker: a wrong answer here either spams an update notification on
         * every run or silently never notifies.
         */
        internal fun isNewer(latest: String, current: String): Boolean {
            val l = parseVersion(latest) ?: return false
            val c = parseVersion(current) ?: return false
            if (l.first != c.first) return l.first > c.first
            if (l.second != c.second) return l.second > c.second
            return l.third > c.third
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ENABLE_UPDATER || BuildConfig.VERSION_NAME.contains("-preview")) {
            return@withContext Result.success()
        }
        try {
            val release = when (val result = fetchLatestRelease()) {
                is FetchResult.Success -> result.release
                is FetchResult.Retry -> return@withContext Result.retry()
                is FetchResult.Skip -> return@withContext Result.success()
            }
            val latestTag = release.optString("tag_name", "").removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v")

            if (latestTag.isBlank() || currentVersion.isBlank()) return@withContext Result.success()

            if (isNewer(latestTag, currentVersion) && !alreadyNotified(latestTag)) {
                if (showNotification()) {
                    markNotified(latestTag)
                }
            }

            Result.success()
        } catch (e: Exception) {
            // Retry only transient I/O failures. Non-I/O errors (e.g. malformed JSON)
            // would otherwise retry indefinitely — skip and let the next periodic run try.
            if (e is java.io.IOException) Result.retry() else Result.success()
        }
    }

    private fun fetchLatestRelease(): FetchResult {
        val url = URL(GITHUB_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Mystx-App")
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val code = connection.responseCode
            if (code in 500..599) return FetchResult.Retry
            if (code != 200) return FetchResult.Skip

            // Bounded read — avoids unbounded memory usage on unexpectedly large responses
            val body = connection.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    if (sb.length + n > MAX_RESPONSE_CHARS) return FetchResult.Skip
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            FetchResult.Success(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Compares semantic versions (e.g. "1.0.50" > "1.0.49").
     * Returns true if [latest] is strictly newer than [current].
     */
    private fun isNewer(latest: String, current: String): Boolean = Companion.isNewer(latest, current)

    private fun alreadyNotified(version: String): Boolean {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_NOTIFIED_VERSION, null) == version
    }

    private fun markNotified(version: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, version).apply()
    }

    private fun showNotification(): Boolean {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !notificationManager.areNotificationsEnabled()) {
            return false
        }

        // Create channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.update_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.update_available_title))
            .setContentText(applicationContext.getString(R.string.update_available_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }
}

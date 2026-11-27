package com.mystx.app.manager

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.mystx.app.ui.billing.AdUnlockActivity

/**
 * Controls AI access for Free vs Pro users:
 * - Mystx Pro users always have unlimited, ad-free AI access.
 * - Free users unlock 6 hours of unlimited AI access per rewarded video ad.
 *
 * Uses both wall-clock time and monotonic elapsedRealtime to resist basic clock tampering.
 */
object AccessGateManager {

    private const val PREFS_NAME = "access_gate"
    private const val KEY_IS_PRO = "is_pro_cached"
    private const val KEY_PASS_WALL_EXPIRY = "ad_pass_wall_expiry"
    private const val KEY_PASS_ELAPSED_EXPIRY = "ad_pass_elapsed_expiry"

    const val PASS_DURATION_HOURS = 6
    const val PASS_DURATION_MS = PASS_DURATION_HOURS * 60L * 60L * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setProStatus(context: Context, isPro: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PRO, isPro).apply()
    }

    fun isPro(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PRO, false)

    fun grantAdPass(context: Context) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putLong(KEY_PASS_WALL_EXPIRY, nowWall + PASS_DURATION_MS)
            .putLong(KEY_PASS_ELAPSED_EXPIRY, nowElapsed + PASS_DURATION_MS)
            .apply()
    }

    fun remainingPassMs(context: Context): Long {
        val p = prefs(context)
        val wallExpiry = p.getLong(KEY_PASS_WALL_EXPIRY, 0L)
        if (wallExpiry <= 0L) return 0L

        val nowWall = System.currentTimeMillis()
        val wallLeft = wallExpiry - nowWall
        if (wallLeft <= 0L) return 0L

        // Guard against backward clock jumps while device hasn't rebooted
        val elapsedExpiry = p.getLong(KEY_PASS_ELAPSED_EXPIRY, 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (elapsedExpiry > 0L && nowElapsed < elapsedExpiry) {
            val elapsedLeft = elapsedExpiry - nowElapsed
            return minOf(wallLeft, elapsedLeft, PASS_DURATION_MS)
        }
        // After a reboot elapsedRealtime resets to 0; trust wallLeft clamped to PASS_DURATION_MS
        return minOf(wallLeft, PASS_DURATION_MS)
    }

    fun remainingPassFormatted(context: Context): String? {
        val ms = remainingPassMs(context)
        if (ms <= 0L) return null
        val totalMinutes = (ms / 60_000L).coerceAtLeast(1L)
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return if (hours > 0L) "${hours}h ${mins}m left" else "${mins}m left"
    }

    fun hasAiAccess(context: Context): Boolean {
        if (isPro(context)) return true
        return remainingPassMs(context) > 0L
    }

    fun launchUnlockPrompt(context: Context) {
        try {
            val intent = Intent(context.applicationContext, AdUnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.applicationContext.startActivity(intent)
        } catch (_: Exception) {}
    }
}

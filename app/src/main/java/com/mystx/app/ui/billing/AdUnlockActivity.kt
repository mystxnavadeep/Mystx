package com.mystx.app.ui.billing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystx.app.BuildConfig
import com.mystx.app.MainActivity
import com.mystx.app.manager.AccessGateManager
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystPill
import com.mystx.app.ui.components.MystTonalButton
import com.mystx.app.ui.theme.MystxTheme
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions

/**
 * Translucent popup shown when a Free user's 6-hour AI pass has expired.
 * Powered by Unity Ads (third-party Rewarded Video SDK — no Google Play Store required).
 * Watching 1 full video ad unlocks 6 hours of unlimited AI; Pro users (₹40/mo · ₹200/yr) see zero ads.
 */
class AdUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-initialize Unity Ads in background so ads load faster when tapped
        try {
            if (!UnityAds.isInitialized()) {
                UnityAds.initialize(
                    applicationContext,
                    BuildConfig.UNITY_GAME_ID,
                    BuildConfig.UNITY_TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {}
                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError?,
                            message: String?
                        ) {}
                    }
                )
            }
        } catch (_: Throwable) {}

        setContent {
            MystxTheme {
                AdUnlockContent(
                    onWatchAd = { onStateUpdate ->
                        initLoadAndShowUnityRewarded(onStateUpdate)
                    },
                    onGoPro = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            putExtra(EXTRA_OPEN_PRO, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun initLoadAndShowUnityRewarded(
        onStateUpdate: (loading: Boolean, message: String?, isError: Boolean) -> Unit
    ) {
        val placementId = BuildConfig.UNITY_REWARDED_PLACEMENT
        onStateUpdate(true, "Loading short video ad...", false)

        val loadAndShow = {
            try {
                UnityAds.load(
                    placementId,
                    object : IUnityAdsLoadListener {
                        override fun onUnityAdsAdLoaded(id: String?) {
                            runOnUiThread {
                                showUnityRewarded(placementId, onStateUpdate)
                            }
                        }

                        override fun onUnityAdsFailedToLoad(
                            id: String?,
                            error: UnityAds.UnityAdsLoadError?,
                            message: String?
                        ) {
                            runOnUiThread {
                                onStateUpdate(
                                    false,
                                    "Could not load video ad right now (${message ?: error?.name ?: "unavailable"}). Try again or Go Pro.",
                                    true
                                )
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                runOnUiThread {
                    onStateUpdate(false, "Ad service unavailable right now. Please try again or Go Pro.", true)
                }
            }
        }

        try {
            if (UnityAds.isInitialized()) {
                loadAndShow()
            } else {
                UnityAds.initialize(
                    applicationContext,
                    BuildConfig.UNITY_GAME_ID,
                    BuildConfig.UNITY_TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            runOnUiThread { loadAndShow() }
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError?,
                            message: String?
                        ) {
                            runOnUiThread {
                                onStateUpdate(
                                    false,
                                    "Ad network init failed (${message ?: error?.name ?: "error"}). Check internet or Go Pro.",
                                    true
                                )
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
            onStateUpdate(false, "Ad service unavailable. Please try again or Go Pro.", true)
        }
    }

    private fun showUnityRewarded(
        placementId: String,
        onStateUpdate: (loading: Boolean, message: String?, isError: Boolean) -> Unit
    ) {
        onStateUpdate(false, null, false)
        try {
            UnityAds.show(
                this,
                placementId,
                UnityAdsShowOptions(),
                object : IUnityAdsShowListener {
                    override fun onUnityAdsShowFailure(
                        id: String?,
                        error: UnityAds.UnityAdsShowError?,
                        message: String?
                    ) {
                        runOnUiThread {
                            onStateUpdate(
                                false,
                                "Failed to play video ad (${message ?: error?.name ?: "error"}). Try again.",
                                true
                            )
                        }
                    }

                    override fun onUnityAdsShowStart(id: String?) {}
                    override fun onUnityAdsShowClick(id: String?) {}

                    override fun onUnityAdsShowComplete(
                        id: String?,
                        state: UnityAds.UnityAdsShowCompletionState?
                    ) {
                        runOnUiThread {
                            if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                                AccessGateManager.grantAdPass(applicationContext)
                                finish()
                            } else {
                                onStateUpdate(
                                    false,
                                    "Watch the full video to unlock 6 hours of AI access.",
                                    true
                                )
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
            onStateUpdate(false, "Unable to open video ad. Please try again or Go Pro.", true)
        }
    }

    companion object {
        const val EXTRA_OPEN_PRO = "extra_open_pro"
    }
}

@Composable
private fun AdUnlockContent(
    onWatchAd: ((loading: Boolean, message: String?, isError: Boolean) -> Unit) -> Unit,
    onGoPro: () -> Unit,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { if (!isLoading) onDismiss() }
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            )
        ) {
            MystCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Unlock 6 Hours of AI",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    MystPill(text = "6H FREE")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Watch 1 short video ad to get 6 hours of unlimited AI commands, or upgrade to Mystx Pro (₹40/mo · ₹200/yr) for zero ads forever.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    MystGradientButton(
                        text = "Watch Video · Unlock 6 Hours",
                        onClick = {
                            onWatchAd { loading, msg, err ->
                                isLoading = loading
                                statusMessage = msg
                                isError = err
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MystTonalButton(
                        text = "Go Pro · ₹40/mo or ₹200/yr (No Ads)",
                        onClick = onGoPro,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(enabled = !isLoading, onClick = onDismiss) {
                        Text(
                            text = "Not Now",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

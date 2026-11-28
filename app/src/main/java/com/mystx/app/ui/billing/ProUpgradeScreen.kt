package com.mystx.app.ui.billing

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.mystx.app.manager.AuthManager
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystDivider
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystIconButton
import com.mystx.app.ui.components.MystPill
import com.mystx.app.ui.components.MystTextField
import com.mystx.app.ui.components.MystTonalButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MYSTX_UPI_ID = "8106379443@fam"
private const val MYSTX_UPI_NAME = "Mystx"
private const val PLAN_MONTHLY = "monthly"
private const val PLAN_YEARLY = "yearly"

@Composable
fun ProUpgradeScreen(
    onBack: () -> Unit,
    onRequireSignIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf(AuthManager.currentUser(context)) }
    var selectedPlan by remember { mutableStateOf(PLAN_YEARLY) }
    var utrInput by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (account != null) {
            AuthManager.fetchUserProfile(context).onSuccess { refreshed ->
                account = refreshed
            }
        }
    }

    val isYearly = selectedPlan == PLAN_YEARLY
    val amountText = if (isYearly) "200.00" else "40.00"
    val amountInt = if (isYearly) 200 else 40

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MystIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Mystx Pro",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Status banner
        MystCard {
            val isPro = account?.isProActive == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = if (isPro) "Mystx Pro Active" else "Upgrade to Pro",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isPro) {
                            "Plan: ${account?.plan?.uppercase()} · All features unlocked"
                        } else {
                            "No ads, no 6-hour timers, all 5 themes & vision explain."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MystPill(text = if (isPro) "PRO" else "FREE")
            }
        }

        // Side-by-side plan selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlanOptionCard(
                title = "Yearly",
                price = "₹200",
                cadence = "/ year",
                subtitle = "₹16.6/mo · Save 58%",
                badge = "BEST",
                selected = isYearly,
                onClick = { selectedPlan = PLAN_YEARLY },
                modifier = Modifier.weight(1f)
            )
            PlanOptionCard(
                title = "Monthly",
                price = "₹40",
                cadence = "/ month",
                subtitle = "Flexible · No ads",
                badge = null,
                selected = !isYearly,
                onClick = { selectedPlan = PLAN_MONTHLY },
                modifier = Modifier.weight(1f)
            )
        }

        MystCard {
            Text(
                text = "Included in Pro",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            val perks = listOf(
                "Zero ads & no 6-hour video unlock waiting",
                "All 5 UI themes (Obsidian, Warm Paper, Carbon Amber, OLED Mono, Material You)",
                "Unlimited custom commands & studio backup sync",
                "Screenshot / Image Vision Explain & Follow-up Mini-Chat",
                "Automatic cross-provider failover (Gemini -> Groq -> Custom)"
            )
            perks.forEach { perk ->
                Text(
                    text = "• $perk",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        val currentAccount = account
        if (currentAccount == null) {
            MystGradientButton(
                text = "Sign In to Upgrade",
                onClick = onRequireSignIn,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val acc = currentAccount
            MystCard {
                Text(
                    text = "Step 1 · Pay via UPI (0% Fee)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = MYSTX_UPI_ID,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MystGradientButton(
                        text = "Pay ₹$amountInt UPI",
                        onClick = {
                            statusMessage = null
                            val note = "Mystx Pro ($selectedPlan) - ${acc.email}"
                            val upiUri = Uri.Builder()
                                .scheme("upi")
                                .authority("pay")
                                .appendQueryParameter("pa", MYSTX_UPI_ID)
                                .appendQueryParameter("pn", MYSTX_UPI_NAME)
                                .appendQueryParameter("tn", note)
                                .appendQueryParameter("am", amountText)
                                .appendQueryParameter("cu", "INR")
                                .build()
                            val intent = Intent(Intent.ACTION_VIEW, upiUri)
                            try {
                                context.startActivity(Intent.createChooser(intent, "Pay ₹$amountInt with UPI"))
                            } catch (_: ActivityNotFoundException) {
                                isError = true
                                statusMessage = "No UPI app found. Copy UPI ID ($MYSTX_UPI_ID) and pay ₹$amountInt manually."
                            } catch (_: Exception) {
                                isError = true
                                statusMessage = "Could not open UPI app. Copy UPI ID ($MYSTX_UPI_ID) and pay ₹$amountInt manually."
                            }
                        },
                        modifier = Modifier.weight(1.2f)
                    )
                    MystTonalButton(
                        text = "Copy UPI",
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Mystx UPI ID", MYSTX_UPI_ID))
                            isError = false
                            statusMessage = "UPI ID copied: $MYSTX_UPI_ID"
                        },
                        modifier = Modifier.weight(0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                MystDivider()
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Step 2 · Submit UTR / Reference ID",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                MystTextField(
                    value = utrInput,
                    onValueChange = { if (!isSubmitting) utrInput = it.filter { ch -> ch.isLetterOrDigit() }.take(24) },
                    label = { Text("12-digit UTR / Transaction ID", fontSize = 13.sp) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (isSubmitting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    MystGradientButton(
                        text = "Submit UTR for Activation",
                        onClick = {
                            val cleanUtr = utrInput.trim()
                            if (cleanUtr.length < 8) {
                                isError = true
                                statusMessage = "Please enter a valid UPI UTR / Transaction ID (at least 8 characters)."
                                return@MystGradientButton
                            }
                            if (!AuthManager.isFirebaseConfigured(context)) {
                                isError = true
                                statusMessage = "Firebase is not configured yet."
                                return@MystGradientButton
                            }
                            scope.launch {
                                isSubmitting = true
                                statusMessage = null
                                try {
                                    val receipt = hashMapOf(
                                        "uid" to acc.uid,
                                        "email" to acc.email,
                                        "plan" to selectedPlan,
                                        "amountInr" to amountInt,
                                        "upiId" to MYSTX_UPI_ID,
                                        "utr" to cleanUtr,
                                        "status" to "pending_verification",
                                        "createdAt" to FieldValue.serverTimestamp()
                                    )
                                    suspendCancellableCoroutine<Unit> { cont ->
                                        FirebaseFirestore.getInstance()
                                            .collection("payments")
                                            .add(receipt)
                                            .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                                            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
                                    }
                                    utrInput = ""
                                    isError = false
                                    statusMessage = "UTR $cleanUtr submitted! Your Mystx Pro ($selectedPlan) will activate right after verification."
                                } catch (e: Exception) {
                                    isError = true
                                    statusMessage = e.message ?: "Could not submit UTR. Please check your internet connection."
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            MystTonalButton(
                text = "Refresh Pro Status",
                onClick = {
                    scope.launch {
                        AuthManager.fetchUserProfile(context).fold(
                            onSuccess = { refreshed ->
                                account = refreshed
                                isError = false
                                statusMessage = if (refreshed.isProActive) {
                                    "Mystx Pro is active on your account!"
                                } else {
                                    "Still on Free plan — check back shortly after UTR verification."
                                }
                            },
                            onFailure = {
                                isError = true
                                statusMessage = "Could not refresh status. Check your internet connection."
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlanOptionCard(
    title: String,
    price: String,
    cadence: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (badge != null) {
                MystPill(text = badge)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = price,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = cadence,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

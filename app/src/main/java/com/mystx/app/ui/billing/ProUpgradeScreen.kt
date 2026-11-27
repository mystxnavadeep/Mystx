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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.mystx.app.manager.AuthManager
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystGradientButton
import com.mystx.app.ui.components.MystPill
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
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Mystx Pro",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Active status banner
        MystCard {
            val isPro = account?.isProActive == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPro) "Mystx Pro Active" else "Upgrade to Mystx Pro",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isPro) {
                            "Plan: ${account?.plan?.uppercase()} · Ad-free & all features unlocked"
                        } else {
                            "Zero ads forever, all 5 UI themes, unlimited commands & vision explain."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MystPill(text = if (isPro) "PRO" else "FREE")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        PlanOptionCard(
            title = "Yearly Pro",
            price = "₹200 / year",
            subtitle = "Just ₹16.6/month · Save 58%",
            badge = "BEST VALUE",
            selected = isYearly,
            onClick = { selectedPlan = PLAN_YEARLY }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PlanOptionCard(
            title = "Monthly Pro",
            price = "₹40 / month",
            subtitle = "Flexible monthly access · No ads",
            badge = null,
            selected = !isYearly,
            onClick = { selectedPlan = PLAN_MONTHLY }
        )

        Spacer(modifier = Modifier.height(14.dp))

        MystCard {
            Text(
                text = "What's Included in Pro",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            val perks = listOf(
                "Zero ads & no 8-hour video unlock waiting",
                "All 5 UI themes (Liquid Glass, Linear Pro, Nothing OS, Material You, Warm Editorial)",
                "Unlimited custom commands & studio backup sync",
                "Screenshot / Image Vision Explain & Follow-up Mini-Chat",
                "Automatic cross-provider failover (Gemini -> Groq -> Custom)"
            )
            perks.forEach { perk ->
                Text(
                    text = "• $perk",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (account == null) {
            MystGradientButton(
                text = "Sign In to Upgrade",
                onClick = onRequireSignIn,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val acc = account!!
            // Direct UPI Payment + UTR Verification Card
            MystCard {
                Text(
                    text = "Step 1 · Pay via UPI (0% Fee)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "UPI ID: $MYSTX_UPI_ID",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(10.dp))

                MystGradientButton(
                    text = "Pay ₹$amountInt via UPI App",
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
                            statusMessage = "No UPI app found. Copy UPI ID ($MYSTX_UPI_ID) and pay ₹$amountInt from any UPI app."
                        } catch (_: Exception) {
                            isError = true
                            statusMessage = "Could not open UPI app. Copy UPI ID ($MYSTX_UPI_ID) and pay ₹$amountInt manually."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                MystTonalButton(
                    text = "Copy UPI ID ($MYSTX_UPI_ID)",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Mystx UPI ID", MYSTX_UPI_ID))
                        isError = false
                        statusMessage = "UPI ID copied: $MYSTX_UPI_ID"
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Step 2 · Submit UPI UTR / Reference ID",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = utrInput,
                    onValueChange = { utrInput = it.filter { ch -> ch.isLetterOrDigit() }.take(24) },
                    label = { Text("12-digit UTR / Transaction ID") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (isSubmitting) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    MystGradientButton(
                        text = "Submit UTR for Pro Activation",
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
                                            .document(cleanUtr)
                                            .set(receipt)
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

            Spacer(modifier = Modifier.height(10.dp))

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 13.sp,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 10.dp)
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
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MystPill(text = badge)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = price,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

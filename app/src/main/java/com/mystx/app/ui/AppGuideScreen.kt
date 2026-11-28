package com.mystx.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mystx.app.ui.components.MystCard
import com.mystx.app.ui.components.MystDivider
import com.mystx.app.ui.components.MystGradientButton

/**
 * Clean, distraction-free in-app guide designed with flat Compose hierarchy,
 * Material vector icons (no emojis), and clear step-by-step instructions.
 */
@Composable
fun AppGuideScreen(
    onBack: () -> Unit,
    onStartTour: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "How to Use Mystx",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        GuideSectionLabel("GETTING STARTED")

        GuideStepCard(
            icon = Icons.Outlined.Key,
            step = "Step 1",
            title = "Add a Free AI API Key",
            body = "Open the Keys tab and paste a free Google Gemini or Groq API key. Your keys stay encrypted on your device in Android Keystore and are never uploaded to our servers."
        )

        GuideStepCard(
            icon = Icons.Outlined.AccessibilityNew,
            step = "Step 2",
            title = "Enable Typing Assistant (Optional)",
            body = "Tap Enable on the Home screen to use inline commands like ?fix in any app. On Android 13+, if you see 'Restricted setting', tap App Info -> Allow restricted settings first."
        )

        GuideSectionLabel("4 WAYS TO USE MYSTX")

        MystCard {
            Text(
                text = "Interactive Quick Guide",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Highlight each button step-by-step — learn how to enable Mystx, add API keys, and create custom slash commands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            MystGradientButton(
                text = "Start Interactive Tour",
                onClick = onStartTour,
                modifier = Modifier.fillMaxWidth()
            )
        }

        MystCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FeatureGuideRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Type Commands Anywhere",
                    description = "Type a sentence followed by ?fix, ?improve, ?formal, or ?reply in WhatsApp, Instagram, or Gmail. Type ? alone to open the draggable command bar."
                )
                MystDivider()
                FeatureGuideRow(
                    icon = Icons.Outlined.TextFields,
                    title = "Highlight Text in Any App",
                    description = "Select any text and tap Mystx Commands or Mystx Explain from the Android text menu — works even without Accessibility permission."
                )
                MystDivider()
                FeatureGuideRow(
                    icon = Icons.Outlined.Image,
                    title = "Explain Screenshots & Photos",
                    description = "Share any screenshot, note, or diagram from your gallery to Mystx Explain to get instant AI explanations and ask follow-up questions."
                )
                MystDivider()
                FeatureGuideRow(
                    icon = Icons.Outlined.PictureAsPdf,
                    title = "Study with Built-in PDF Reader",
                    description = "Tap Read PDF on Home, highlight any paragraph, and tap Mystx Explain for live-streamed explanations and follow-up mini-chat."
                )
            }
        }

        GuideSectionLabel("QUICK COMMANDS")

        MystCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val shortcuts = listOf(
                    "?fix" to "Fix grammar & spelling",
                    "?improve" to "Make text clearer & smoother",
                    "?shorten" to "Summarize into fewer words",
                    "?expand" to "Add helpful detail",
                    "?formal" to "Rewrite in professional tone",
                    "?reply" to "Draft a smart reply from screen context",
                    "?tr" to "Translate into your chosen language"
                )
                shortcuts.forEachIndexed { idx, (trigger, desc) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = trigger,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (idx < shortcuts.lastIndex) {
                        MystDivider()
                    }
                }
            }
        }

        GuideSectionLabel("FREE ACCESS & PRO")

        GuideStepCard(
            icon = Icons.Outlined.WorkspacePremium,
            step = "Access",
            title = "6-Hour Free Pass or Mystx Pro",
            body = "Free users can watch 1 short video ad to unlock 6 hours of unlimited AI commands. Upgrade to Mystx Pro (₹40/month or ₹200/year via UPI) for zero ads forever."
        )
    }
}

@Composable
private fun GuideSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun GuideStepCard(
    icon: ImageVector,
    step: String,
    title: String,
    body: String
) {
    MystCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeatureGuideRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

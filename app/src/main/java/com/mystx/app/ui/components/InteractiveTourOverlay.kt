package com.mystx.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TourStep(
    val title: String,
    val description: String,
    val tapHint: String,
    val spotlightTopDp: Dp,
    val spotlightStartDp: Dp,
    val spotlightEndDp: Dp,
    val spotlightHeightDp: Dp,
    val cardAtBottom: Boolean = true
)

/**
 * Interactive coachmark spotlight overlay:
 * - Blocks accidental background taps with a 74% dark scrim
 * - Cuts out a clear spotlight window around the target UI control
 * - Lets the user advance either by tapping the highlighted spotlight or tapping Next,
 *   or exit anytime with Skip.
 */
@Composable
fun InteractiveTourOverlay(
    stepIndex: Int,
    steps: List<TourStep>,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    if (stepIndex !in steps.indices) return
    val step = steps[stepIndex]
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary

    val animTop by animateDpAsState(targetValue = step.spotlightTopDp, animationSpec = tween(260), label = "top")
    val animStart by animateDpAsState(targetValue = step.spotlightStartDp, animationSpec = tween(260), label = "start")
    val animEnd by animateDpAsState(targetValue = step.spotlightEndDp, animationSpec = tween(260), label = "end")
    val animHeight by animateDpAsState(targetValue = step.spotlightHeightDp, animationSpec = tween(260), label = "height")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Consume all touches outside the spotlight so background stays safely blocked
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val clampedStartDp = animStart.coerceAtMost((maxWidth - 110.dp).coerceAtLeast(14.dp))
        val topPx = with(density) { animTop.toPx() }
        val startPx = with(density) { clampedStartDp.toPx() }
        val endPx = with(density) { animEnd.toPx() }
        val heightPx = with(density) { animHeight.toPx() }
        val rectWidthPx = (totalWidthPx - startPx - endPx).coerceAtLeast(48f)
        val cornerPx = with(density) { 14.dp.toPx() }

        // Dimmed scrim with transparent spotlight cutout
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.74f))
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(startPx, topPx),
                size = Size(rectWidthPx, heightPx),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                blendMode = BlendMode.Clear
            )
        }

        // Interactive glowing ring over the spotlight — tapping it advances the tour
        Box(
            modifier = Modifier
                .padding(top = animTop, start = clampedStartDp, end = animEnd)
                .fillMaxWidth()
                .height(animHeight)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, primaryColor, RoundedCornerShape(14.dp))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNext()
                }
        )

        // Floating Coachmark Card (positioned away from the spotlight)
        val isLast = stepIndex == steps.lastIndex
        Surface(
            modifier = Modifier
                .align(if (step.cardAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .navigationBarsPadding()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = if (step.cardAtBottom) 82.dp else 16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f)),
            shadowElevation = 10.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MystPill(text = "STEP ${stepIndex + 1} OF ${steps.size}")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        steps.indices.forEach { i ->
                            Box(
                                modifier = Modifier
                                    .size(if (i == stepIndex) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i == stepIndex) primaryColor
                                        else MaterialTheme.colorScheme.outline
                                    )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = step.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = step.description,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = step.tapHint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onSkip) {
                        Text(
                            text = "Skip Tour",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    MystGradientButton(
                        text = if (isLast) "Got It ✓" else "Next →",
                        onClick = onNext
                    )
                }
            }
        }
    }
}

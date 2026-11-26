package com.mystx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mystx.app.ui.theme.LocalMystUiStyle
import com.mystx.app.ui.theme.MystUiStyle

/**
 * Theme-aware backdrop adapting automatically to the user's selected MystUiStyle.
 */
@Composable
fun MystAuroraBackdrop(modifier: Modifier = Modifier) {
    when (LocalMystUiStyle.current) {
        MystUiStyle.LIQUID_GLASS -> {
            val baseTop = Color(0xFF000000)
            val baseBottom = Color(0xFF0F0F13)
            val blobAlpha = 0.5f
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(baseTop, baseBottom)))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-80).dp, y = (-50).dp)
                        .size(400.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF0A2B4C).copy(alpha = blobAlpha), Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 100.dp, y = 100.dp)
                        .size(450.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF2A0A3A).copy(alpha = blobAlpha), Color.Transparent)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = 150.dp)
                        .size(300.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF003020).copy(alpha = blobAlpha), Color.Transparent)
                            )
                        )
                )
            }
        }
        MystUiStyle.LINEAR_PRO -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0C0F))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-120).dp)
                        .size(420.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF312E81).copy(alpha = 0.32f), Color.Transparent)
                            )
                        )
                )
            }
        }
        MystUiStyle.NOTHING_OS -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
            )
        }
        MystUiStyle.MATERIAL_YOU, MystUiStyle.WARM_EDITORIAL -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }
    }
}

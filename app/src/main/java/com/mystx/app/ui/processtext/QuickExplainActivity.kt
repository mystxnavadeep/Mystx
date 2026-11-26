package com.mystx.app.ui.processtext

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mystx.app.MystxApp
import com.mystx.app.R
import com.mystx.app.api.ApiClientUtils
import com.mystx.app.api.GeminiClient
import com.mystx.app.api.OpenAICompatibleClient
import com.mystx.app.model.HistoryManager
import com.mystx.app.model.PrefKeys
import com.mystx.app.service.CommandOutcome
import com.mystx.app.service.ErrorMessages
import com.mystx.app.service.runTextCommand
import com.mystx.app.ui.theme.MystxTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class QuickExplainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        if (action != Intent.ACTION_PROCESS_TEXT && action != Intent.ACTION_SEND) {
            finish()
            return
        }

        val rawText = when (action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        val selection = ProcessTextInput.parseSelection(rawText, readOnlyExtra = true).getOrNull()
        if (selection == null) {
            finish()
            return
        }
        val selectedText = selection.text

        // Full-screen transparent window so Compose-level offset moves the card smoothly
        // without mutating window.attributes (which caused pointer-delta feedback jitter).
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        setContent {
            MystxTheme(forceLight = true) {
                QuickExplainScreen(
                    selectedText = selectedText,
                    onClose = { finish() }
                )
            }
        }
    }
}

private const val QUICK_EXPLAIN_TIMEOUT_MS = 90_000L

@Composable
fun QuickExplainScreen(selectedText: String, onClose: () -> Unit) {
    var result by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var triggerFetch by remember { mutableStateOf(0) }
    var skipCache by remember { mutableStateOf(false) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val config = LocalConfiguration.current
    val maxDragX = ((config.screenWidthDp - 320f).coerceAtLeast(0f) / 2f + 120f) * density
    val maxDragY = ((config.screenHeightDp - 240f).coerceAtLeast(0f) / 2f + 120f) * density

    LaunchedEffect(selectedText, triggerFetch) {
        isLoading = true
        val bypassCache = skipCache
        try {
            val resolvedResult = withContext(Dispatchers.IO) {
                val keyManager = (context.applicationContext as MystxApp).keyManager
                val geminiClient = GeminiClient()
                val openAIClient = OpenAICompatibleClient()

                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val lang = prefs.getString(PrefKeys.EXPLAIN_LANGUAGE, "Tenglish") ?: "Tenglish"

                val prompt = """
                    Analyze the provided text.
                    If it is a single word or short phrase (like a name, URL, or domain), provide its meaning or identify what it is.
                    If it is a question, provide a concise answer.
                    Please respond natively in $lang.
                """.trimIndent()

                val cached = if (bypassCache) {
                    null
                } else {
                    HistoryManager.findCachedResponse(context, selectedText, "QuickExplain")
                }
                if (!cached.isNullOrBlank()) {
                    return@withContext cached
                }

                val outcome = try {
                    withTimeout(QUICK_EXPLAIN_TIMEOUT_MS) {
                        runTextCommand(
                            context, keyManager, geminiClient, openAIClient,
                            prompt, selectedText
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    CommandOutcome.Failure(context.getString(R.string.toast_request_timed_out))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val safeRaw = ApiClientUtils.redactSecrets(e.message ?: "")
                    CommandOutcome.Failure(context.getString(ErrorMessages.map(safeRaw)))
                }

                when (outcome) {
                    is CommandOutcome.Success -> {
                        if (outcome.text.isNotBlank()) {
                            HistoryManager.addEntry(context, selectedText, "QuickExplain", outcome.text, true)
                        }
                        outcome.text
                    }
                    is CommandOutcome.Failure -> ApiClientUtils.redactSecrets(outcome.message)
                    is CommandOutcome.Refusal -> context.getString(R.string.error_safety_blocked)
                    is CommandOutcome.Unavailable -> ApiClientUtils.redactSecrets(outcome.message)
                }
            }
            result = resolvedResult
        } finally {
            skipCache = false
            isLoading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(320.dp)
                .heightIn(max = 400.dp)
                .clickable(interactionSource = null, indication = null) {},
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(maxDragX, maxDragY) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(-maxDragX, maxDragX)
                                offsetY = (offsetY + dragAmount.y).coerceIn(-maxDragY, maxDragY)
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mystx Explain",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Regenerate",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    skipCache = true
                                    triggerFetch++
                                }
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.clickable { onClose() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "\"$selectedText\"",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        text = result ?: "",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

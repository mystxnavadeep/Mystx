package com.mystx.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.mystx.app.service.runTextCommandStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val POPUP_REQUEST_TIMEOUT_MS = 90_000L
private const val MAX_EXPLAIN_TEXT_CHARS = 8_000
private const val MIN_POPUP_WIDTH_DP = 250f
private const val MIN_POPUP_HEIGHT_DP = 250f
private const val MAX_POPUP_FOLLOWUP_TURNS = 6
private const val MAX_POPUP_FOLLOWUP_CHARS = 8_000

private fun buildPopupFollowUpTranscript(history: List<Pair<String, String>>): String {
    val recent = history.takeLast(MAX_POPUP_FOLLOWUP_TURNS)
    val joined = recent.joinToString("\n\n") { (role, content) ->
        val speaker = if (role == "user") "User" else "Assistant"
        "$speaker: $content"
    }
    return if (joined.length > MAX_POPUP_FOLLOWUP_CHARS) {
        joined.takeLast(MAX_POPUP_FOLLOWUP_CHARS)
    } else {
        joined
    }
}

@Composable
fun DraggableExplainPopup(
    selectedText: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density
    val config = LocalConfiguration.current
    val maxWidthDp = (config.screenWidthDp - 16f).coerceAtLeast(MIN_POPUP_WIDTH_DP)
    val maxHeightDp = (config.screenHeightDp - 48f).coerceAtLeast(MIN_POPUP_HEIGHT_DP)
    val screenWidthPx = config.screenWidthDp * density
    val screenHeightPx = config.screenHeightDp * density

    val boundedText = remember(selectedText) { selectedText.take(MAX_EXPLAIN_TEXT_CHARS) }

    var width by remember { mutableStateOf(320f.coerceIn(MIN_POPUP_WIDTH_DP, maxWidthDp)) }
    var height by remember { mutableStateOf(400f.coerceIn(MIN_POPUP_HEIGHT_DP, maxHeightDp)) }
    var offsetX by remember { mutableStateOf(50f.coerceIn(0f, (screenWidthPx - width * density).coerceAtLeast(0f))) }
    var offsetY by remember { mutableStateOf(200f.coerceIn(0f, (screenHeightPx - height * density).coerceAtLeast(0f))) }

    var result by remember { mutableStateOf<String?>(null) }
    var streamingText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var triggerFetch by remember { mutableStateOf(0) }
    var skipCache by remember { mutableStateOf(false) }

    val chatHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var followUpInput by remember { mutableStateOf("") }
    var isFollowUpLoading by remember { mutableStateOf(false) }
    var followUpStreamingText by remember { mutableStateOf("") }
    var followUpJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(streamingText.length, followUpStreamingText.length, chatHistory.size) {
        if (streamingText.isNotEmpty() || followUpStreamingText.isNotEmpty() || chatHistory.size > 2) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(boundedText, triggerFetch) {
        followUpJob?.cancel()
        isFollowUpLoading = false
        followUpStreamingText = ""
        isLoading = true
        streamingText = ""
        result = null
        chatHistory.clear()
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
                    HistoryManager.findCachedResponse(context, boundedText, "QuickExplain", isSelection = true)
                }
                if (!cached.isNullOrBlank()) {
                    return@withContext cached
                }

                val streamedBuffer = StringBuilder()
                val outcome = try {
                    withTimeout(POPUP_REQUEST_TIMEOUT_MS) {
                        runTextCommandStream(
                            context, prefs, keyManager, geminiClient, openAIClient,
                            prompt, boundedText
                        ) { chunk ->
                            val snapshot = synchronized(streamedBuffer) {
                                streamedBuffer.append(chunk)
                                streamedBuffer.toString()
                            }
                            coroutineScope.launch(Dispatchers.Main) {
                                streamingText = snapshot
                            }
                        }
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
                            HistoryManager.addEntry(context, boundedText, "QuickExplain", outcome.text, true)
                        }
                        outcome.text
                    }
                    is CommandOutcome.Failure -> ApiClientUtils.redactSecrets(outcome.message)
                    is CommandOutcome.Refusal -> context.getString(R.string.error_safety_blocked)
                    is CommandOutcome.Unavailable -> ApiClientUtils.redactSecrets(outcome.message)
                }
            }
            result = resolvedResult
            streamingText = ""
            if (resolvedResult.isNotBlank()) {
                chatHistory.add("user" to boundedText)
                chatHistory.add("assistant" to resolvedResult)
            }
        } finally {
            skipCache = false
            isLoading = false
        }
    }

    fun sendFollowUp() {
        val question = followUpInput.trim()
        if (question.isEmpty() || isFollowUpLoading || isLoading) return

        followUpInput = ""
        chatHistory.add("user" to question)
        val transcript = buildPopupFollowUpTranscript(chatHistory.toList())
        isFollowUpLoading = true
        followUpStreamingText = ""

        followUpJob = coroutineScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val keyManager = (context.applicationContext as MystxApp).keyManager
                    val geminiClient = GeminiClient()
                    val openAIClient = OpenAICompatibleClient()
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val lang = prefs.getString(PrefKeys.EXPLAIN_LANGUAGE, "Tenglish") ?: "Tenglish"

                    val followUpPrompt = """
                        Continue the conversation below by answering the user's latest follow-up question concisely and accurately.
                        Please respond natively in $lang.
                    """.trimIndent()

                    val streamedBuffer = StringBuilder()
                    val outcome = try {
                        withTimeout(POPUP_REQUEST_TIMEOUT_MS) {
                            runTextCommandStream(
                                context, prefs, keyManager, geminiClient, openAIClient,
                                followUpPrompt, transcript
                            ) { chunk ->
                                val snapshot = synchronized(streamedBuffer) {
                                    streamedBuffer.append(chunk)
                                    streamedBuffer.toString()
                                }
                                coroutineScope.launch(Dispatchers.Main) {
                                    followUpStreamingText = snapshot
                                }
                            }
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
                        is CommandOutcome.Success -> outcome.text
                        is CommandOutcome.Failure -> ApiClientUtils.redactSecrets(outcome.message)
                        is CommandOutcome.Refusal -> context.getString(R.string.error_safety_blocked)
                        is CommandOutcome.Unavailable -> ApiClientUtils.redactSecrets(outcome.message)
                    }
                }
                followUpStreamingText = ""
                if (reply.isNotBlank()) {
                    chatHistory.add("assistant" to reply)
                }
            } finally {
                followUpStreamingText = ""
                isFollowUpLoading = false
            }
        }
    }

    val isStreamingInitial = isLoading && streamingText.isNotEmpty()
    val isBusy = isLoading || isFollowUpLoading

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width.dp, height.dp)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .pointerInput(screenWidthPx, screenHeightPx, density) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val maxOffsetX = (screenWidthPx - width * density).coerceAtLeast(0f)
                            val maxOffsetY = (screenHeightPx - height * density).coerceAtLeast(0f)
                            offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffsetX)
                            offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxOffsetY)
                        }
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✨ Mystx Explain", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    if (isStreamingInitial || isFollowUpLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isBusy) {
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
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.clickable { onClose() })
                }
            }

            Column(modifier = Modifier.padding(16.dp).weight(1f)) {
                Text(
                    text = "\"$boundedText\"",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading && streamingText.isEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Fetching answer...", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isLoading) {
                                streamingText
                            } else if (result.isNullOrBlank()) {
                                "Error: API returned an empty response. Try selecting a different text."
                            } else {
                                result.orEmpty()
                            },
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val followUpTurns = if (chatHistory.size > 2) chatHistory.drop(2) else emptyList()
                        followUpTurns.forEach { (role, message) ->
                            val isUser = role == "user"
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isUser) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        }
                                    )
                                    .padding(10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = if (isUser) "You" else "Mystx",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isUser) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Text(
                                        text = message,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (isFollowUpLoading) {
                            if (followUpStreamingText.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Mystx",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = followUpStreamingText,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }

                    if (!isLoading && !result.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = followUpInput,
                                onValueChange = { followUpInput = it },
                                placeholder = { Text("Ask a follow-up...", fontSize = 13.sp) },
                                singleLine = true,
                                enabled = !isFollowUpLoading,
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendFollowUp() }),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { sendFollowUp() },
                                enabled = followUpInput.isNotBlank() && !isFollowUpLoading
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send follow-up",
                                    tint = if (followUpInput.isNotBlank() && !isFollowUpLoading) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .background(Color.Transparent)
                .pointerInput(density, maxWidthDp, maxHeightDp, screenWidthPx, screenHeightPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        width = (width + dragAmount.x / density).coerceIn(MIN_POPUP_WIDTH_DP, maxWidthDp)
                        height = (height + dragAmount.y / density).coerceIn(MIN_POPUP_HEIGHT_DP, maxHeightDp)
                        offsetX = offsetX.coerceIn(0f, (screenWidthPx - width * density).coerceAtLeast(0f))
                        offsetY = offsetY.coerceIn(0f, (screenHeightPx - height * density).coerceAtLeast(0f))
                    }
                }
        ) {
            Text("◢", modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

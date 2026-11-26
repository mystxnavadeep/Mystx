package com.mystx.app.ui.processtext

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.mystx.app.service.runImageCommand
import com.mystx.app.service.runTextCommandStream
import com.mystx.app.ui.theme.MystxTheme
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
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

        val isImageShare = action == Intent.ACTION_SEND && intent?.type?.startsWith("image/") == true

        val selectedText: String?
        val imageUri: Uri?

        if (isImageShare) {
            imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (imageUri == null) {
                finish()
                return
            }
            selectedText = null
        } else {
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
            selectedText = selection.text
            imageUri = null
        }

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
                    imageUri = imageUri,
                    onClose = { finish() }
                )
            }
        }
    }
}

private const val QUICK_EXPLAIN_TIMEOUT_MS = 90_000L
private const val MAX_IMAGE_DIMENSION_PX = 1280
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val INITIAL_JPEG_QUALITY = 82
private const val MAX_FOLLOWUP_TURNS = 6
private const val MAX_FOLLOWUP_CHARS = 8_000

private data class DecodedImagePayload(
    val preview: ImageBitmap,
    val jpegBytes: ByteArray
)

private fun decodeAndCompressImage(context: Context, uri: Uri): Result<DecodedImagePayload> {
    return try {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: return Result.failure(IllegalStateException("Could not open shared image."))

        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) {
            return Result.failure(IllegalArgumentException("Unsupported or corrupted image."))
        }

        var sampleSize = 1
        while (maxOf(origW / sampleSize, origH / sampleSize) > MAX_IMAGE_DIMENSION_PX) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return Result.failure(IllegalStateException("Failed to decode shared image."))

        val preview = bitmap.asImageBitmap()
        val qualitySteps = intArrayOf(INITIAL_JPEG_QUALITY, 65, 50, 35)
        var jpegBytes: ByteArray? = null
        for (q in qualitySteps) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos)
            val candidate = baos.toByteArray()
            if (candidate.size <= MAX_IMAGE_BYTES) {
                jpegBytes = candidate
                break
            }
        }

        if (jpegBytes == null) {
            Result.failure(IllegalArgumentException("Shared image exceeds the 4 MB size limit."))
        } else {
            Result.success(DecodedImagePayload(preview = preview, jpegBytes = jpegBytes))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun buildFollowUpTranscript(history: List<Pair<String, String>>): String {
    val recent = history.takeLast(MAX_FOLLOWUP_TURNS)
    val joined = recent.joinToString("\n\n") { (role, content) ->
        val speaker = if (role == "user") "User" else "Assistant"
        "$speaker: $content"
    }
    return if (joined.length > MAX_FOLLOWUP_CHARS) {
        joined.takeLast(MAX_FOLLOWUP_CHARS)
    } else {
        joined
    }
}

@Composable
fun QuickExplainScreen(
    selectedText: String?,
    imageUri: Uri? = null,
    onClose: () -> Unit
) {
    var result by remember { mutableStateOf<String?>(null) }
    var streamingText by remember { mutableStateOf("") }
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var triggerFetch by remember { mutableStateOf(0) }
    var skipCache by remember { mutableStateOf(false) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val chatHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var followUpInput by remember { mutableStateOf("") }
    var isFollowUpLoading by remember { mutableStateOf(false) }
    var followUpStreamingText by remember { mutableStateOf("") }
    var followUpJob by remember { mutableStateOf<Job?>(null) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density
    val config = LocalConfiguration.current
    val maxDragX = ((config.screenWidthDp - 320f).coerceAtLeast(0f) / 2f + 120f) * density
    val maxDragY = ((config.screenHeightDp - 240f).coerceAtLeast(0f) / 2f + 120f) * density

    LaunchedEffect(streamingText.length, followUpStreamingText.length, chatHistory.size) {
        if (streamingText.isNotEmpty() || followUpStreamingText.isNotEmpty() || chatHistory.size > 2) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(selectedText, imageUri, triggerFetch) {
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

                if (imageUri != null) {
                    val decoded = decodeAndCompressImage(context, imageUri).getOrElse { err ->
                        return@withContext ApiClientUtils.redactSecrets(
                            err.message ?: "Could not process the shared image."
                        )
                    }
                    withContext(Dispatchers.Main) {
                        previewBitmap = decoded.preview
                    }

                    val imagePrompt = """
                        Analyze the provided image or screenshot carefully.
                        If it contains a question, problem, or exercise, solve and explain it clearly step by step.
                        If it shows text, a diagram, UI, or object, explain what it is and highlight the key details.
                        Please respond natively in $lang.
                    """.trimIndent()

                    val outcome = try {
                        withTimeout(QUICK_EXPLAIN_TIMEOUT_MS) {
                            runImageCommand(
                                context, prefs, keyManager, geminiClient,
                                imagePrompt, decoded.jpegBytes, "image/jpeg"
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
                        is CommandOutcome.Success -> outcome.text
                        is CommandOutcome.Failure -> ApiClientUtils.redactSecrets(outcome.message)
                        is CommandOutcome.Refusal -> context.getString(R.string.error_safety_blocked)
                        is CommandOutcome.Unavailable -> ApiClientUtils.redactSecrets(outcome.message)
                    }
                } else {
                    val textToExplain = selectedText ?: return@withContext ""
                    val prompt = """
                        Analyze the provided text.
                        If it is a single word or short phrase (like a name, URL, or domain), provide its meaning or identify what it is.
                        If it is a question, provide a concise answer.
                        Please respond natively in $lang.
                    """.trimIndent()

                    val cached = if (bypassCache) {
                        null
                    } else {
                        HistoryManager.findCachedResponse(context, textToExplain, "QuickExplain", isSelection = true)
                    }
                    if (!cached.isNullOrBlank()) {
                        return@withContext cached
                    }

                    val streamedBuffer = StringBuilder()
                    val outcome = try {
                        withTimeout(QUICK_EXPLAIN_TIMEOUT_MS) {
                            runTextCommandStream(
                                context, prefs, keyManager, geminiClient, openAIClient,
                                prompt, textToExplain
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
                                HistoryManager.addEntry(context, textToExplain, "QuickExplain", outcome.text, true)
                            }
                            outcome.text
                        }
                        is CommandOutcome.Failure -> ApiClientUtils.redactSecrets(outcome.message)
                        is CommandOutcome.Refusal -> context.getString(R.string.error_safety_blocked)
                        is CommandOutcome.Unavailable -> ApiClientUtils.redactSecrets(outcome.message)
                    }
                }
            }
            result = resolvedResult
            streamingText = ""
            if (resolvedResult.isNotBlank()) {
                val initialUserPrompt = if (imageUri != null) {
                    "[Shared image/screenshot]"
                } else {
                    selectedText.orEmpty()
                }
                chatHistory.add("user" to initialUserPrompt)
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
        val transcript = buildFollowUpTranscript(chatHistory.toList())
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
                        withTimeout(QUICK_EXPLAIN_TIMEOUT_MS) {
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
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(320.dp)
                .heightIn(max = 480.dp)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (imageUri != null) "Mystx Vision" else "Mystx Explain",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.clickable { onClose() }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (imageUri != null) {
                    val thumb = previewBitmap
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = "Shared image preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Text(
                            text = "Analyzing shared image...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (!selectedText.isNullOrBlank()) {
                    Text(
                        text = "\"$selectedText\"",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (isLoading && streamingText.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isLoading) streamingText else (result ?: ""),
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
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
    }
}

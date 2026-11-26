package com.mystx.app.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.grapheneos.pdfviewer.PdfViewerScreen
import app.grapheneos.pdfviewer.viewModel.PdfViewModel
import java.util.concurrent.atomic.AtomicReference

@Composable
fun PdfReaderScreen(
    pdfUri: Uri,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val viewModel: PdfViewModel = viewModel()

    var selectedText by remember { mutableStateOf<String?>(null) }
    var isPopupVisible by remember { mutableStateOf(false) }
    var isExplainButtonVisible by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val pendingSelection = remember { AtomicReference<Runnable?>(null) }

    LaunchedEffect(pdfUri) {
        viewModel.setUri(pdfUri)
        viewModel.resetDocumentState()
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSelection.getAndSet(null)?.let { mainHandler.removeCallbacks(it) }
            viewModel.maybeCloseInputStream()
            viewModel.encryptedDocumentPassword = ""
            (context as? Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view)
                    .show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PdfViewerScreen(
            viewModel = viewModel,
            initialMimeError = false,
            onRequestRecreate = {},
            onWebViewCreated = {},
            onWebViewDestroyed = {},
            onClose = onClose,
            onSelectionChanged = { text ->
                val capped = text.take(8_000)
                val runnable = Runnable {
                    val trimmed = capped.trim()
                    if (trimmed.isNotEmpty() && trimmed != "null") {
                        if (!isPopupVisible) {
                            selectedText = trimmed
                            isExplainButtonVisible = true
                        }
                    } else {
                        isExplainButtonVisible = false
                        if (!isPopupVisible) {
                            selectedText = null
                        }
                    }
                }
                pendingSelection.getAndSet(runnable)?.let { mainHandler.removeCallbacks(it) }
                mainHandler.postDelayed(runnable, 150L)
            }
        )

        AnimatedVisibility(
            visible = isExplainButtonVisible && !isPopupVisible,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    isExplainButtonVisible = false
                    isPopupVisible = true
                },
                icon = { Text("✨") },
                text = { Text("Mystx Explain", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        AnimatedVisibility(
            visible = isPopupVisible,
            enter = fadeIn() + slideInHorizontally(),
            exit = fadeOut() + slideOutHorizontally()
        ) {
            selectedText?.let { currentSelection ->
                DraggableExplainPopup(
                    selectedText = currentSelection,
                    onClose = {
                        isPopupVisible = false
                        selectedText = null
                    }
                )
            }
        }
    }
}

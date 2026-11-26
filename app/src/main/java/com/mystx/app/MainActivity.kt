package com.mystx.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mystx.app.model.PrefKeys
import com.mystx.app.ui.DashboardScreen
import com.mystx.app.ui.KeysScreen
import com.mystx.app.ui.SettingsScreen
import com.mystx.app.ui.components.MystAuroraBackdrop
import com.mystx.app.ui.components.MystDock
import com.mystx.app.ui.components.MystDockItem
import com.mystx.app.ui.studio.CommandEditorScreen
import com.mystx.app.ui.studio.CommandStudioScreen
import com.mystx.app.ui.theme.MystxTheme

enum class Tab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Dashboard(R.string.dashboard_title, Icons.Default.Home),
    Keys(R.string.keys_title, Icons.Default.Lock),
    Commands(R.string.command_studio_title, Icons.AutoMirrored.Filled.List),
    History(R.string.history_title, Icons.Default.DateRange),
    Settings(R.string.settings_title, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private var incomingPdfUri by mutableStateOf<Uri?>(null)

    private fun extractPdfUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW && intent.type == "application/pdf") {
            intent.data
        } else {
            null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            incomingPdfUri = extractPdfUri(intent)
        }

        setContent {
            MystxTheme {
                MystxMainScreen(
                    incomingPdfUri = incomingPdfUri,
                    onIncomingPdfConsumed = { incomingPdfUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractPdfUri(intent)?.let { incomingPdfUri = it }
    }
}

@Composable
fun MystxMainScreen(
    vm: MystxViewModel = viewModel(),
    incomingPdfUri: Uri? = null,
    onIncomingPdfConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Dashboard) }

    var studioSubScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var pdfUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfUri = remember(pdfUriString) { pdfUriString?.let { Uri.parse(it) } }

    LaunchedEffect(incomingPdfUri) {
        if (incomingPdfUri != null) {
            pdfUriString = incomingPdfUri.toString()
            onIncomingPdfConsumed()
        }
    }

    val isEditing = studioSubScreen?.startsWith("editor") == true
    val editingCommand = remember(studioSubScreen) {
        val sub = studioSubScreen
        if (sub != null && sub.startsWith("editor_") && sub != "editor_new") {
            val id = sub.removePrefix("editor_")
            vm.commandStudioStore.getRichCommands().firstOrNull { it.id == id }
        } else {
            null
        }
    }

    BackHandler(enabled = pdfUri != null || studioSubScreen != null || (selectedTab == Tab.Commands && isEditing)) {
        if (pdfUri != null) {
            pdfUriString = null
        } else if (isEditing) {
            if (selectedTab == Tab.Settings) {
                studioSubScreen = "studio_from_settings"
            } else {
                studioSubScreen = null
            }
        } else if (studioSubScreen == "studio_from_settings") {
            studioSubScreen = null
        } else if (selectedTab == Tab.Commands) {
            selectedTab = Tab.Dashboard
        }
    }

    // Request notification permission on first launch (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> // Result not needed — we just need to prompt once
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean("notification_permission_requested", false)
                if (!alreadyRequested) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MystAuroraBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .statusBarsPadding()
                    .imePadding()
            ) {
                if (pdfUri != null) {
                    com.mystx.app.ui.PdfReaderScreen(pdfUri = pdfUri) {
                        pdfUriString = null
                    }
                } else when {
                    isEditing -> {
                        CommandEditorScreen(
                            initialCommand = editingCommand,
                            store = vm.commandStudioStore,
                            keyManager = vm.keyManager,
                            geminiClient = vm.geminiClient,
                            openAIClient = vm.openAIClient,
                            prefs = vm.prefs,
                            onBack = {
                                if (selectedTab == Tab.Settings) {
                                    studioSubScreen = "studio_from_settings"
                                } else {
                                    studioSubScreen = null
                                }
                            }
                        )
                    }
                    selectedTab == Tab.Settings && studioSubScreen == "studio_from_settings" -> {
                        CommandStudioScreen(
                            store = vm.commandStudioStore,
                            globalTemperature = vm.prefs.getFloat(PrefKeys.TEMPERATURE, 0.5f),
                            onEditCommand = { cmd ->
                                studioSubScreen = "editor_${cmd.id}"
                            },
                            onCreateCommand = {
                                studioSubScreen = "editor_new"
                            },
                            onBack = { studioSubScreen = null }
                        )
                    }
                    selectedTab == Tab.Commands -> {
                        CommandStudioScreen(
                            store = vm.commandStudioStore,
                            globalTemperature = vm.prefs.getFloat(PrefKeys.TEMPERATURE, 0.5f),
                            onEditCommand = { cmd ->
                                studioSubScreen = "editor_${cmd.id}"
                            },
                            onCreateCommand = {
                                studioSubScreen = "editor_new"
                            },
                            onBack = { selectedTab = Tab.Dashboard }
                        )
                    }
                    selectedTab == Tab.Dashboard -> {
                        DashboardScreen(
                            keyManager = vm.keyManager,
                            commandManager = vm.commandManager,
                            statsManager = vm.statsManager,
                            onOpenPdf = { uri -> pdfUriString = uri.toString() }
                        )
                    }
                    selectedTab == Tab.History -> {
                        com.mystx.app.ui.HistoryScreen()
                    }
                    selectedTab == Tab.Keys -> {
                        KeysScreen(vm.keyManager, vm.prefs)
                    }
                    selectedTab == Tab.Settings -> {
                        SettingsScreen(
                            commandManager = vm.commandManager,
                            commandStudioStore = vm.commandStudioStore,
                            prefs = vm.prefs,
                            keyManager = vm.keyManager,
                            onNavigateToCommandStudio = { studioSubScreen = "studio_from_settings" }
                        )
                    }
                }
            }
        }

        // Floating glass dock — hovers over the content, centered, above the nav bar inset.
        if (!isEditing && pdfUri == null) {
            MystDock(
                items = Tab.entries.map { MystDockItem(icon = it.icon, labelRes = it.titleRes) },
                selected = selectedTab.ordinal,
                onSelect = { index ->
                    studioSubScreen = null
                    selectedTab = Tab.entries[index]
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .widthIn(max = 420.dp)
            )
        }
    }
}

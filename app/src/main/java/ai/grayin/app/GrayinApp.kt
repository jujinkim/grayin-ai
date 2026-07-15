package ai.grayin.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ai.grayin.core.security.AppLockState
import ai.grayin.core.security.AppSecurityAuthCapability
import ai.grayin.core.security.AppSecurityFailure
import ai.grayin.core.security.AppSecurityState
import ai.grayin.core.indexing.ManualIndexDateRange
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.grayin.core.transfer.TransferFailureCode
import ai.grayin.core.transfer.TransferResult

enum class GrayinScreen {
    Ask,
    Timeline,
    Places,
    Sources,
    Settings,
}

private enum class BackupDialogMode {
    NONE,
    EXPORT_PASSWORD,
    IMPORT_CONFIRMATION,
    IMPORT_PASSWORD,
}

fun initialScreenForSourcesIntro(hasSeenSourcesIntro: Boolean): GrayinScreen {
    return if (hasSeenSourcesIntro) GrayinScreen.Ask else GrayinScreen.Sources
}

class SourceIntroPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSeenSourcesIntro(): Boolean {
        return prefs.getBoolean(KEY_SOURCES_INTRO_SEEN, false)
    }

    fun markSourcesIntroSeen() {
        prefs.edit().putBoolean(KEY_SOURCES_INTRO_SEEN, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "grayin_sources_intro"
        const val KEY_SOURCES_INTRO_SEEN = "sources_intro_seen"
    }
}

@Composable
fun GrayinApp(
    appSecurityState: AppSecurityState,
    onUnlockApp: () -> Unit,
    onScreenshotBlockingChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { GrayinMemoryController(context) }
    val languageStore = remember(context) { LanguagePreferenceStore(context) }
    val sourceIntroStore = remember(context) { SourceIntroPreferenceStore(context) }
    val automaticIndexingStore = remember(context) { AutomaticIndexingPreferenceStore(context) }
    val automaticIndexingScheduler = remember(context) { AutomaticIndexingScheduler(context) }
    val scope = rememberCoroutineScope()
    var selectedScreenName by rememberSaveable {
        mutableStateOf(initialScreenForSourcesIntro(sourceIntroStore.hasSeenSourcesIntro()).name)
    }
    var languageOptionName by rememberSaveable { mutableStateOf(languageStore.load().name) }
    val languageOption = GrayinLanguageOption.valueOf(languageOptionName)
    val strings = remember(languageOption) { GrayinText.forOption(languageOption) }
    var snapshot by remember { mutableStateOf(emptySnapshot(strings)) }
    var answerState by remember { mutableStateOf(emptyAnswerState(strings)) }
    var statusMessage by remember { mutableStateOf("") }
    var hasAsked by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var automaticIndexingState by remember { mutableStateOf(automaticIndexingStore.load()) }
    var automaticIndexingSyncing by remember { mutableStateOf(false) }
    var automaticIndexingSyncRevision by remember { mutableStateOf(0L) }
    var backupDialogModeName by rememberSaveable { mutableStateOf(BackupDialogMode.NONE.name) }
    var pendingExportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSettingsConnectorId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirmation by remember { mutableStateOf("") }
    val selectedScreen = GrayinScreen.valueOf(selectedScreenName)
    val backupDialogMode = BackupDialogMode.valueOf(backupDialogModeName)
    val screens = GrayinScreen.entries

    LaunchedEffect(appSecurityState.protectedContentVisible) {
        if (!appSecurityState.protectedContentVisible) {
            backupPassword = ""
            backupPasswordConfirmation = ""
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                backupPassword = ""
                backupPasswordConfirmation = ""
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun refreshSnapshotSafely() {
        try {
            snapshot = controller.snapshot(strings)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (statusMessage.isBlank()) {
                statusMessage = strings.indexingFailed
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                try {
                    statusMessage = controller.rememberSelectedLocalFile(uri, strings)
                } catch (_: Throwable) {
                    statusMessage = strings.localFileSelectionFailed
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        }
    }
    val modelDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                try {
                    statusMessage = controller.importLocalGemmaModel(uri, strings)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    statusMessage = strings.localGemmaModelImportFailed
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        }
    }
    val createBackupDocumentLauncher = rememberLauncherForActivityResult(
        contract = CreateLocalBackupDocumentContract(),
    ) { uri ->
        val token = pendingExportToken
        pendingExportToken = null
        if (token != null) {
            scope.launch {
                working = true
                try {
                    statusMessage = if (uri == null) {
                        controller.discardEncryptedBackupStage(token)
                        strings.backupCanceled()
                    } else {
                        when (val result = controller.writePreparedEncryptedExport(token, uri)) {
                            is TransferResult.Success -> strings.backupExportSucceeded()
                            is TransferResult.Failure -> strings.backupFailure(result.failure.code)
                        }
                    }
                } finally {
                    working = false
                }
            }
        }
    }
    val openBackupDocumentLauncher = rememberLauncherForActivityResult(
        contract = OpenLocalBackupDocumentContract(),
    ) { uri ->
        if (uri == null) {
            statusMessage = strings.backupCanceled()
        } else {
            scope.launch {
                working = true
                try {
                    when (val result = controller.stageEncryptedImport(uri)) {
                        is TransferResult.Success -> {
                            pendingImportToken = result.value.token
                            backupPassword = ""
                            backupDialogModeName = BackupDialogMode.IMPORT_PASSWORD.name
                            statusMessage = ""
                        }

                        is TransferResult.Failure -> {
                            statusMessage = strings.backupFailure(result.failure.code)
                        }
                    }
                } finally {
                    working = false
                }
            }
        }
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                working = true
                try {
                    statusMessage = strings.connectorConnectionResult(
                        controller.connectConnector(CalendarConnectorId),
                    )
                } catch (_: Throwable) {
                    statusMessage = strings.sourceConnectionFailed()
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        } else {
            statusMessage = strings.sourcePermissionDenied
        }
    }
    val photosPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scope.launch {
                working = true
                try {
                    statusMessage = strings.connectorConnectionResult(
                        controller.connectConnector(PhotosConnectorId),
                    )
                } catch (_: Throwable) {
                    statusMessage = strings.sourceConnectionFailed()
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        } else {
            statusMessage = strings.sourcePermissionDenied
        }
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            scope.launch {
                working = true
                try {
                    statusMessage = strings.connectorConnectionResult(
                        controller.connectConnector(LocationConnectorId),
                    )
                } catch (_: Throwable) {
                    statusMessage = strings.sourceConnectionFailed()
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        } else {
            statusMessage = strings.sourcePermissionDenied
        }
    }
    val connectorSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val returned = ConnectorSettingsAccessState(pendingSettingsConnectorId).consumeReturn()
        pendingSettingsConnectorId = returned.nextState.pendingConnectorId
        val connectorId = returned.connectorId
        if (connectorId != null) {
            scope.launch {
                working = true
                try {
                    statusMessage = strings.connectorConnectionResult(
                        controller.connectConnector(connectorId),
                    )
                } catch (_: Throwable) {
                    statusMessage = strings.sourceConnectionFailed()
                } finally {
                    refreshSnapshotSafely()
                    working = false
                }
            }
        }
    }

    fun openConnectorSettings(connectorId: String) {
        val pendingState = try {
            ConnectorSettingsAccessState(pendingSettingsConnectorId).begin(connectorId)
        } catch (_: IllegalArgumentException) {
            statusMessage = strings.settingsAccessAlreadyPending()
            return
        }
        val destination = ConnectorSettingsAccessState.destinationForConnector(connectorId)
            ?: return
        pendingSettingsConnectorId = pendingState.pendingConnectorId
        try {
            connectorSettingsLauncher.launch(
                Intent(
                    when (destination) {
                        ConnectorSettingsDestination.USAGE_ACCESS -> Settings.ACTION_USAGE_ACCESS_SETTINGS
                        ConnectorSettingsDestination.NOTIFICATION_LISTENER_ACCESS ->
                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    },
                ),
            )
        } catch (_: Throwable) {
            pendingSettingsConnectorId = pendingState.cancel().pendingConnectorId
            statusMessage = strings.settingsAccessOpenFailed()
        }
    }

    fun refreshSnapshot() {
        scope.launch {
            refreshSnapshotSafely()
        }
    }

    fun indexLocalFiles() {
        scope.launch {
            working = true
            try {
                statusMessage = controller.indexLocalFiles(strings)
            } catch (_: Throwable) {
                statusMessage = strings.indexingFailed
            } finally {
                refreshSnapshotSafely()
                working = false
            }
        }
    }

    fun indexAllSources() {
        scope.launch {
            working = true
            try {
                statusMessage = controller.indexAllEnabledSources(strings)
            } catch (_: Throwable) {
                statusMessage = strings.indexingFailed
            } finally {
                refreshSnapshotSafely()
                working = false
            }
        }
    }

    fun updateAutomaticIndexing(state: AutomaticIndexingUiState) {
        if (!state.hasValidWindow) {
            statusMessage = strings.invalidAutomaticIndexingWindow
            return
        }
        automaticIndexingSyncRevision += 1L
        val revision = automaticIndexingSyncRevision
        automaticIndexingStore.save(state)
        automaticIndexingState = state
        automaticIndexingSyncing = true
        statusMessage = ""
        val syncRequest = automaticIndexingScheduler.requestSync()
        scope.launch {
            try {
                syncRequest.await()
                if (revision == automaticIndexingSyncRevision) {
                    statusMessage = strings.automaticIndexingSaved(state.enabled)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (revision == automaticIndexingSyncRevision) {
                    statusMessage = strings.indexingFailed
                }
            } finally {
                if (revision == automaticIndexingSyncRevision) {
                    automaticIndexingSyncing = false
                    try {
                        val indexingStatus = controller.indexingStatus(strings)
                        snapshot = snapshot.copy(indexingStatus = indexingStatus)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        statusMessage = strings.indexingFailed
                    }
                }
            }
        }
    }

    LaunchedEffect(sourceIntroStore) {
        if (!sourceIntroStore.hasSeenSourcesIntro()) {
            sourceIntroStore.markSourcesIntroSeen()
        }
    }

    LaunchedEffect(controller) {
        runCatching { controller.cleanupStaleEncryptedBackupStages() }
    }

    LaunchedEffect(automaticIndexingScheduler) {
        val revision = automaticIndexingSyncRevision
        automaticIndexingSyncing = true
        val syncRequest = automaticIndexingScheduler.requestSync()
        try {
            syncRequest.await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (revision == automaticIndexingSyncRevision) {
                statusMessage = strings.indexingFailed
            }
        } finally {
            if (revision == automaticIndexingSyncRevision) {
                automaticIndexingSyncing = false
            }
        }
    }

    LaunchedEffect(controller, strings) {
        refreshSnapshotSafely()
        if (!hasAsked) {
            answerState = emptyAnswerState(strings)
        }
    }

    LaunchedEffect(selectedScreen, controller, strings, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (selectedScreen) {
                GrayinScreen.Sources -> while (true) {
                    try {
                        val indexingStatus = controller.indexingStatus(strings)
                        snapshot = snapshot.copy(indexingStatus = indexingStatus)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        statusMessage = strings.indexingFailed
                    }
                    delay(StatusRefreshIntervalMs)
                }

                GrayinScreen.Settings -> while (true) {
                    try {
                        snapshot = controller.snapshot(strings)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        statusMessage = strings.indexingFailed
                    }
                    delay(StatusRefreshIntervalMs)
                }

                else -> Unit
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF245C4A),
            secondary = Color(0xFF6B5F2A),
            tertiary = Color(0xFF7E3F4F),
            surface = Color(0xFFFCFCF8),
            background = Color(0xFFF7F8F4),
        ),
    ) {
        if (!appSecurityState.protectedContentVisible) {
            AppLockScreen(
                state = appSecurityState,
                strings = strings,
                onUnlock = onUnlockApp,
                onOpenDeviceSecuritySettings = onOpenDeviceSecuritySettings,
            )
        } else {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                bottomBar = {
                    NavigationBar {
                        screens.forEach { screen ->
                            NavigationBarItem(
                                selected = screen == selectedScreen,
                                onClick = { selectedScreenName = screen.name },
                                icon = {
                                    Icon(
                                        imageVector = screen.icon(),
                                        contentDescription = strings.screenLabel(screen),
                                    )
                                },
                                label = { Text(strings.screenLabel(screen)) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (selectedScreen) {
                    GrayinScreen.Ask -> AskScreen(
                        answerState = answerState,
                        strings = strings,
                        working = working,
                        onAsk = { query ->
                            scope.launch {
                                working = true
                                hasAsked = true
                                try {
                                    answerState = controller.ask(query, strings)
                                } catch (_: Throwable) {
                                    answerState = AnswerUiState(
                                        answer = strings.searchFailed,
                                        confidence = strings.confidenceLabel(ai.grayin.core.model.ConfidenceLevel.UNKNOWN),
                                        evidenceRows = listOf(strings.noCitedEvidence),
                                        missingRows = listOf(strings.tryIndexingAgain),
                                    )
                                } finally {
                                    working = false
                                    refreshSnapshot()
                                }
                            }
                        },
                    )

                    GrayinScreen.Timeline -> TimelineScreen(snapshot.timelineRows, strings)
                    GrayinScreen.Places -> PlacesScreen(snapshot.placesRows, strings)
                    GrayinScreen.Sources -> SourcesScreen(
                        sourceRows = snapshot.sourceRows,
                        indexingStatus = snapshot.indexingStatus,
                        automaticIndexingState = automaticIndexingState,
                        automaticIndexingSyncing = automaticIndexingSyncing,
                        statusMessage = statusMessage,
                        strings = strings,
                        working = working || pendingSettingsConnectorId != null,
                        onIndexAllSources = ::indexAllSources,
                        onAutomaticIndexingChanged = ::updateAutomaticIndexing,
                        onAddLocalFile = {
                            openDocumentLauncher.launch(LocalDocumentPickerContract.mimeTypes())
                        },
                        onInvokeSource = { connectorId, requiredPermissions ->
                            when {
                                connectorId == CalendarConnectorId &&
                                    Manifest.permission.READ_CALENDAR in requiredPermissions -> {
                                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }

                                connectorId == PhotosConnectorId && requiredPermissions.isNotEmpty() -> {
                                    photosPermissionLauncher.launch(requiredPermissions.first())
                                }

                                connectorId == LocationConnectorId && requiredPermissions.isNotEmpty() -> {
                                    locationPermissionLauncher.launch(requiredPermissions.toTypedArray())
                                }

                                connectorId == AppUsageConnectorId -> {
                                    scope.launch {
                                        working = true
                                        try {
                                            val result = controller.connectConnector(connectorId)
                                            statusMessage = strings.connectorConnectionResult(result)
                                            if (result is ConnectorConnectionResult.PermissionRequired) {
                                                openConnectorSettings(connectorId)
                                            }
                                        } catch (_: Throwable) {
                                            statusMessage = strings.sourceConnectionFailed()
                                        } finally {
                                            refreshSnapshotSafely()
                                            working = false
                                        }
                                    }
                                }

                                connectorId == NotificationConnectorId -> {
                                    scope.launch {
                                        working = true
                                        try {
                                            val result = controller.connectConnector(connectorId)
                                            statusMessage = strings.connectorConnectionResult(result)
                                            if (result is ConnectorConnectionResult.PermissionRequired) {
                                                openConnectorSettings(connectorId)
                                            }
                                        } catch (_: Throwable) {
                                            statusMessage = strings.sourceConnectionFailed()
                                        } finally {
                                            refreshSnapshotSafely()
                                            working = false
                                        }
                                    }
                                }

                                else -> {
                                    statusMessage = strings.connectorConnectionUnavailable
                                }
                            }
                        },
                        onIndexSource = { connectorId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.indexConnector(connectorId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.indexingFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onIndexSourceDateRange = { connectorId, days ->
                            scope.launch {
                                working = true
                                try {
                                    val today = LocalDate.now()
                                    statusMessage = controller.indexConnectorDateRange(
                                        connectorId = connectorId,
                                        range = ManualIndexDateRange(
                                            startDateInclusive = today.minusDays(days.toLong() - 1L),
                                            endDateInclusive = today,
                                        ),
                                        strings = strings,
                                    )
                                } catch (_: Throwable) {
                                    statusMessage = strings.indexingFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onRevokeSource = { connectorId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.revokeConnector(connectorId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.revokeFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onDeleteSourceData = { connectorId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.deleteConnectorData(connectorId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.deleteFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onSaveNotificationAllowlist = { rawValue ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.updateNotificationAllowlist(rawValue, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.notificationAllowlistInvalid
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onOnlineEnrichmentChanged = { enabled ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.updateOnlineEnrichment(enabled, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.searchFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                    )
                    GrayinScreen.Settings -> SettingsScreen(
                        appSecurityState = appSecurityState,
                        rows = snapshot.settingsRows,
                        modelOptions = snapshot.modelOptions,
                        ocrLanguagePacks = snapshot.ocrLanguagePacks,
                        statusMessage = statusMessage,
                        languageOption = languageOption,
                        strings = strings,
                        working = working,
                        onScreenshotBlockingChanged = onScreenshotBlockingChanged,
                        onAppLockChanged = onAppLockChanged,
                        onOpenDeviceSecuritySettings = onOpenDeviceSecuritySettings,
                        onExportBackup = {
                            backupPassword = ""
                            backupPasswordConfirmation = ""
                            backupDialogModeName = BackupDialogMode.EXPORT_PASSWORD.name
                            statusMessage = ""
                        },
                        onImportBackup = {
                            backupDialogModeName = BackupDialogMode.IMPORT_CONFIRMATION.name
                            statusMessage = ""
                        },
                        onLanguageSelected = { option ->
                            languageStore.save(option)
                            languageOptionName = option.name
                            statusMessage = ""
                        },
                        onIndex = ::indexLocalFiles,
                        onOpenModelDownload = { pageUrl ->
                            try {
                                val url = pageUrl ?: LocalGemmaModelDownloadPage
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Throwable) {
                                statusMessage = strings.localGemmaModelDownloadOpenFailed
                            }
                        },
                        onSelectModel = { modelId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.selectLocalModel(modelId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.localModelUnknown
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onDownloadModel = { modelId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.downloadLocalModel(modelId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.localModelDownloadUnavailable
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onCancelModelDownload = { modelId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.cancelLocalModelDownload(modelId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.deleteFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onDeleteDownloadedModel = { modelId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.deleteDownloadedLocalModel(modelId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.deleteFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onDownloadOcrLanguagePack = { packId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.downloadOcrLanguagePack(packId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.ocrLanguagePackActionFailed()
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onCancelOcrLanguagePackDownload = { packId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.cancelOcrLanguagePackDownload(packId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.ocrLanguagePackActionFailed()
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onDeleteOcrLanguagePack = { packId ->
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.deleteOcrLanguagePack(packId, strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.ocrLanguagePackActionFailed()
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                        onImportModel = {
                            modelDocumentLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onDeleteModel = {
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.deleteLocalGemmaModel(strings)
                                } catch (_: Throwable) {
                                    statusMessage = strings.deleteFailed
                                } finally {
                                    refreshSnapshotSafely()
                                    working = false
                                }
                            }
                        },
                    )
                    }
                }
            }
        }
    }

    if (appSecurityState.protectedContentVisible) {
        when (backupDialogMode) {
            BackupDialogMode.NONE -> Unit
            BackupDialogMode.EXPORT_PASSWORD -> BackupPasswordDialog(
            title = strings.exportEncryptedBackup(),
            body = strings.backupExportPasswordBody(),
            password = backupPassword,
            confirmation = backupPasswordConfirmation,
            errorMessage = statusMessage,
            working = working,
            strings = strings,
            onPasswordChanged = { backupPassword = it },
            onConfirmationChanged = { backupPasswordConfirmation = it },
            onDismiss = {
                backupPassword = ""
                backupPasswordConfirmation = ""
                backupDialogModeName = BackupDialogMode.NONE.name
                statusMessage = ""
            },
            onConfirm = {
                if (backupPassword != backupPasswordConfirmation) {
                    statusMessage = strings.backupPasswordsDoNotMatch()
                } else {
                    val transientPassword = backupPassword.toCharArray()
                    backupPassword = ""
                    backupPasswordConfirmation = ""
                    scope.launch {
                        working = true
                        try {
                            when (val result = controller.prepareEncryptedExport(transientPassword)) {
                                is TransferResult.Success -> {
                                    pendingExportToken = result.value.token
                                    backupDialogModeName = BackupDialogMode.NONE.name
                                    statusMessage = ""
                                    createBackupDocumentLauncher.launch(result.value.suggestedFileName)
                                }

                                is TransferResult.Failure -> {
                                    statusMessage = strings.backupFailure(result.failure.code)
                                }
                            }
                        } finally {
                            transientPassword.fill('\u0000')
                            working = false
                        }
                    }
                }
            },
        )

            BackupDialogMode.IMPORT_CONFIRMATION -> BackupImportConfirmationDialog(
            strings = strings,
            working = working,
            onDismiss = {
                backupDialogModeName = BackupDialogMode.NONE.name
                statusMessage = ""
            },
            onConfirm = {
                backupDialogModeName = BackupDialogMode.NONE.name
                openBackupDocumentLauncher.launch(Unit)
            },
        )

            BackupDialogMode.IMPORT_PASSWORD -> BackupPasswordDialog(
            title = strings.importEncryptedBackup(),
            body = strings.backupImportPasswordBody(),
            password = backupPassword,
            confirmation = null,
            errorMessage = statusMessage,
            working = working,
            strings = strings,
            onPasswordChanged = { backupPassword = it },
            onConfirmationChanged = {},
            onDismiss = {
                val token = pendingImportToken
                pendingImportToken = null
                backupPassword = ""
                backupDialogModeName = BackupDialogMode.NONE.name
                statusMessage = ""
                if (token != null) scope.launch { controller.discardEncryptedBackupStage(token) }
            },
            onConfirm = {
                val token = pendingImportToken
                if (token == null) {
                    backupDialogModeName = BackupDialogMode.NONE.name
                    statusMessage = strings.backupFailure(TransferFailureCode.SOURCE_IO_FAILED)
                } else {
                    val transientPassword = backupPassword.toCharArray()
                    backupPassword = ""
                    scope.launch {
                        working = true
                        try {
                            when (val result = controller.importStagedEncryptedBackup(token, transientPassword)) {
                                is TransferResult.Success -> {
                                    pendingImportToken = null
                                    backupDialogModeName = BackupDialogMode.NONE.name
                                    automaticIndexingState = automaticIndexingStore.load()
                                    statusMessage = strings.backupImportSucceeded(
                                        eventCount = result.value.derivedMemoryEventCount,
                                        connectorCount = result.value.connectorCount,
                                    )
                                    refreshSnapshotSafely()
                                }

                                is TransferResult.Failure -> {
                                    statusMessage = strings.backupFailure(result.failure.code)
                                    if (
                                        result.failure.code == TransferFailureCode.CONSENT_RESET_FAILED ||
                                        result.failure.code == TransferFailureCode.STORE_TRANSACTION_FAILED
                                    ) {
                                        automaticIndexingState = automaticIndexingStore.load()
                                        refreshSnapshotSafely()
                                    }
                                    if (!BackupTransferPolicy.retainImportStageAfter(result.failure.code)) {
                                        pendingImportToken = null
                                        backupDialogModeName = BackupDialogMode.NONE.name
                                    }
                                }
                            }
                        } finally {
                            transientPassword.fill('\u0000')
                            working = false
                        }
                    }
                }
            },
            )
        }
    }
}

@Composable
private fun AppLockScreen(
    state: AppSecurityState,
    strings: GrayinStrings,
    onUnlock: () -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(strings.appLockScreenTitle(), style = MaterialTheme.typography.headlineMedium)
            Text(
                strings.appLockScreenBody(),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                strings.appLockStatus(state.lockState),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            state.failure?.let { failure ->
                Text(
                    strings.appSecurityFailure(failure),
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                enabled = state.activeAttempt == null,
                onClick = onUnlock,
            ) {
                Text(
                    if (state.failure == null) strings.unlockApp() else strings.retryAuthentication(),
                )
            }
            if (state.failure.requiresDeviceSecuritySettings()) {
                OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    enabled = state.activeAttempt == null,
                    onClick = onOpenDeviceSecuritySettings,
                ) {
                    Text(strings.openDeviceSecuritySettings())
                }
            }
        }
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    body: String,
    password: String,
    confirmation: String?,
    errorMessage: String,
    working: Boolean,
    strings: GrayinStrings,
    onPasswordChanged: (String) -> Unit,
    onConfirmationChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = onPasswordChanged,
                    label = { Text(strings.backupPassword()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (confirmation != null) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = confirmation,
                        onValueChange = onConfirmationChanged,
                        label = { Text(strings.backupConfirmPassword()) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (errorMessage.isNotBlank()) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && password.isNotEmpty() &&
                    (confirmation == null || confirmation.isNotEmpty()),
                onClick = onConfirm,
            ) {
                Text(strings.confirm())
            }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) {
                Text(strings.cancel())
            }
        },
    )
}

@Composable
private fun BackupImportConfirmationDialog(
    strings: GrayinStrings,
    working: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(strings.backupImportWarningTitle()) },
        text = { Text(strings.backupImportWarningBody()) },
        confirmButton = {
            TextButton(enabled = !working, onClick = onConfirm) {
                Text(strings.continueAction())
            }
        },
        dismissButton = {
            TextButton(enabled = !working, onClick = onDismiss) {
                Text(strings.cancel())
            }
        },
    )
}

@Composable
private fun AskScreen(
    answerState: AnswerUiState,
    strings: GrayinStrings,
    working: Boolean,
    onAsk: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(strings.ask, style = MaterialTheme.typography.headlineMedium)
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text(strings.memoryQuestion) },
                singleLine = false,
                minLines = 3,
            )
        }
        item {
            Button(
                enabled = query.isNotBlank() && !working,
                onClick = { onAsk(query) },
            ) {
                Text(if (working) strings.searching else strings.search)
            }
        }
        item {
            AnswerCard(
                answer = answerState.answer,
                confidence = answerState.confidence,
                evidenceRows = answerState.evidenceRows,
                missingRows = answerState.missingRows,
                strings = strings,
            )
        }
    }
}

@Composable
private fun AnswerCard(
    answer: String,
    confidence: String,
    evidenceRows: List<String>,
    missingRows: List<String>,
    strings: GrayinStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.answer, style = MaterialTheme.typography.titleMedium)
            Text(answer, style = MaterialTheme.typography.bodyLarge)
            ConfidenceLabel(confidence, strings)
            HorizontalDivider()
            EvidenceSection(evidenceRows, strings)
            HorizontalDivider()
            MissingDataSection(missingRows, strings)
        }
    }
}

@Composable
private fun ConfidenceLabel(confidence: String, strings: GrayinStrings) {
    Text(
        text = "${strings.confidencePrefix} $confidence",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EvidenceSection(rows: List<String>, strings: GrayinStrings) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(strings.evidence, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) strings.hide else strings.show)
            }
        }
        if (expanded) {
            rows.forEach { row ->
                Text("- $row", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(strings.itemCount(rows.size), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MissingDataSection(rows: List<String>, strings: GrayinStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.missingData, style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            Text("- $row", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TimelineScreen(rows: List<String>, strings: GrayinStrings) {
    SimpleListScreen(
        title = strings.timeline,
        rows = rows,
    )
}

@Composable
private fun PlacesScreen(rows: List<String>, strings: GrayinStrings) {
    SimpleListScreen(
        title = strings.places,
        rows = rows,
    )
}

@Composable
private fun SourcesScreen(
    sourceRows: List<ConnectorUiState>,
    indexingStatus: IndexingStatusUiState,
    automaticIndexingState: AutomaticIndexingUiState,
    automaticIndexingSyncing: Boolean,
    statusMessage: String,
    strings: GrayinStrings,
    working: Boolean,
    onIndexAllSources: () -> Unit,
    onAutomaticIndexingChanged: (AutomaticIndexingUiState) -> Unit,
    onAddLocalFile: () -> Unit,
    onInvokeSource: (String, List<String>) -> Unit,
    onIndexSource: (String) -> Unit,
    onIndexSourceDateRange: (String, Int) -> Unit,
    onRevokeSource: (String) -> Unit,
    onDeleteSourceData: (String) -> Unit,
    onSaveNotificationAllowlist: (String) -> Unit,
    onOnlineEnrichmentChanged: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(strings.sources, style = MaterialTheme.typography.headlineMedium)
        }
        item {
            SourceIndexingControls(
                automaticIndexingState = automaticIndexingState,
                strings = strings,
                working = working || automaticIndexingSyncing,
                onIndexAllSources = onIndexAllSources,
                onAutomaticIndexingChanged = onAutomaticIndexingChanged,
            )
        }
        item {
            IndexingStatusBlock(
                status = indexingStatus,
                strings = strings,
            )
        }
        item {
            SourceInvocationCard(strings)
        }
        if (statusMessage.isNotBlank()) {
            item {
                StatusRow(statusMessage)
            }
        }
        items(sourceRows) { source ->
            SourceRow(
                source = source,
                strings = strings,
                working = working,
                onAddLocalFile = onAddLocalFile,
                onInvokeSource = onInvokeSource,
                onIndexSource = onIndexSource,
                onIndexSourceDateRange = onIndexSourceDateRange,
                onRevokeSource = onRevokeSource,
                onDeleteSourceData = onDeleteSourceData,
                onSaveNotificationAllowlist = onSaveNotificationAllowlist,
                onOnlineEnrichmentChanged = onOnlineEnrichmentChanged,
            )
        }
    }
}

@Composable
internal fun IndexingStatusBlock(
    status: IndexingStatusUiState,
    strings: GrayinStrings,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.indexingStatusTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                text = strings.indexingLiveStatus(status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
            )
            strings.indexingStatusRows(status).forEach { row ->
                Text(row, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text(strings.recentIndexingTasks, style = MaterialTheme.typography.titleSmall)
            if (status.recentTasks.isEmpty()) {
                Text(strings.noRecentIndexingTasks, style = MaterialTheme.typography.bodySmall)
            } else {
                status.recentTasks.forEach { task ->
                    Text("- ${strings.recentIndexingTaskRow(task)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SourceIndexingControls(
    automaticIndexingState: AutomaticIndexingUiState,
    strings: GrayinStrings,
    working: Boolean,
    onIndexAllSources: () -> Unit,
    onAutomaticIndexingChanged: (AutomaticIndexingUiState) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                onClick = onIndexAllSources,
            ) {
                Text(if (working) strings.indexing else strings.indexAllNow)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = automaticIndexingState.enabled,
                        enabled = !working && automaticIndexingState.hasValidWindow,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            onAutomaticIndexingChanged(automaticIndexingState.copy(enabled = enabled))
                        },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(strings.automaticIndexing, style = MaterialTheme.typography.titleMedium)
                    Text(
                        strings.automaticIndexingSummary(automaticIndexingState),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = automaticIndexingState.enabled,
                    enabled = !working && automaticIndexingState.hasValidWindow,
                    onCheckedChange = null,
                )
            }
            TextButton(
                enabled = !working,
                onClick = { expanded = !expanded },
            ) {
                Text(if (expanded) strings.hide else strings.automaticIndexingSettings)
            }
            if (expanded) {
                AutomaticIndexingDetails(
                    automaticIndexingState = automaticIndexingState,
                    strings = strings,
                    working = working,
                    onAutomaticIndexingChanged = onAutomaticIndexingChanged,
                )
            }
        }
    }
}

@Composable
private fun AutomaticIndexingDetails(
    automaticIndexingState: AutomaticIndexingUiState,
    strings: GrayinStrings,
    working: Boolean,
    onAutomaticIndexingChanged: (AutomaticIndexingUiState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = automaticIndexingState.requireCharging,
                    enabled = !working,
                    role = Role.Switch,
                    onValueChange = { requireCharging ->
                        onAutomaticIndexingChanged(
                            automaticIndexingState.copy(requireCharging = requireCharging),
                        )
                    },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.chargingOnly, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = automaticIndexingState.requireCharging,
                enabled = !working,
                onCheckedChange = null,
            )
        }
        HourStepper(
            label = strings.startHour,
            hour = automaticIndexingState.startHour,
            working = working,
            canDecrease = automaticIndexingState.shiftedStartHour(-1).hasValidWindow,
            canIncrease = automaticIndexingState.shiftedStartHour(1).hasValidWindow,
            decreaseDescription = strings.decreaseHourDescription(strings.startHour),
            increaseDescription = strings.increaseHourDescription(strings.startHour),
            onDecrease = {
                onAutomaticIndexingChanged(automaticIndexingState.shiftedStartHour(-1))
            },
            onIncrease = {
                onAutomaticIndexingChanged(automaticIndexingState.shiftedStartHour(1))
            },
        )
        HourStepper(
            label = strings.endHour,
            hour = automaticIndexingState.endHour,
            working = working,
            canDecrease = automaticIndexingState.shiftedEndHour(-1).hasValidWindow,
            canIncrease = automaticIndexingState.shiftedEndHour(1).hasValidWindow,
            decreaseDescription = strings.decreaseHourDescription(strings.endHour),
            increaseDescription = strings.increaseHourDescription(strings.endHour),
            onDecrease = {
                onAutomaticIndexingChanged(automaticIndexingState.shiftedEndHour(-1))
            },
            onIncrease = {
                onAutomaticIndexingChanged(automaticIndexingState.shiftedEndHour(1))
            },
        )
        Text(
            strings.automaticIndexingWindow(automaticIndexingState.windowLabel()),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!automaticIndexingState.hasValidWindow) {
            Text(
                strings.invalidAutomaticIndexingWindow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun HourStepper(
    label: String,
    hour: Int,
    working: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decreaseDescription: String,
    increaseDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(AutomaticIndexingUiState.formatHour(hour), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !working && canDecrease,
                onClick = onDecrease,
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = decreaseDescription,
                )
            }
            Button(
                enabled = !working && canIncrease,
                onClick = onIncrease,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = increaseDescription,
                )
            }
        }
    }
}

@Composable
private fun SourceInvocationCard(strings: GrayinStrings) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.sourceConnectionTitle, style = MaterialTheme.typography.titleMedium)
            Text(strings.sourceConnectionBody, style = MaterialTheme.typography.bodyLarge)
            Text(strings.sourceConnectionPrivacyNote, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SourceRow(
    source: ConnectorUiState,
    strings: GrayinStrings,
    working: Boolean,
    onAddLocalFile: () -> Unit,
    onInvokeSource: (String, List<String>) -> Unit,
    onIndexSource: (String) -> Unit,
    onIndexSourceDateRange: (String, Int) -> Unit,
    onRevokeSource: (String) -> Unit,
    onDeleteSourceData: (String) -> Unit,
    onSaveNotificationAllowlist: (String) -> Unit,
    onOnlineEnrichmentChanged: (Boolean) -> Unit,
) {
    var notificationAllowlistText by rememberSaveable(source.id) {
        mutableStateOf(source.notificationAllowedPackages.joinToString("\n"))
    }
    LaunchedEffect(source.notificationAllowedPackages) {
        notificationAllowlistText = source.notificationAllowedPackages.joinToString("\n")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text(source.status, style = MaterialTheme.typography.labelLarge)
            }
            Text(source.sensitivity, style = MaterialTheme.typography.bodySmall)
            source.detailRows.forEach { detail ->
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            source.lastRequestedScanRange?.let { range ->
                Text(strings.requestedScanDateRangeLabel(range), style = MaterialTheme.typography.bodySmall)
            }
            if (source.id == LocalFilesConnectorId) {
                Text(strings.localDocumentSupportDisclosure(), style = MaterialTheme.typography.bodySmall)
            }
            source.onlineEnrichmentEnabled?.let { enabled ->
                Text(strings.onlineEnrichmentTitle, style = MaterialTheme.typography.titleSmall)
                Text(strings.onlineEnrichmentDisclosure, style = MaterialTheme.typography.bodySmall)
                Text(strings.onlineEnrichmentProviderCredit, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(strings.onlineEnrichmentTitle)
                    Switch(
                        checked = enabled,
                        enabled = !working,
                        onCheckedChange = onOnlineEnrichmentChanged,
                    )
                }
            }
            if (source.id == NotificationConnectorId) {
                Text(strings.notificationAllowlistTitle, style = MaterialTheme.typography.titleSmall)
                Text(strings.notificationAllowlistHint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = notificationAllowlistText,
                    enabled = !working,
                    minLines = 2,
                    onValueChange = { notificationAllowlistText = it },
                    label = { Text(strings.notificationAllowlistTitle) },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working,
                    onClick = { onSaveNotificationAllowlist(notificationAllowlistText) },
                ) {
                    Text(strings.saveNotificationAllowlist)
                }
            }
            if (source.canInvoke || source.canAdd || source.canIndex || source.canRevoke || source.canDelete) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source.canInvoke) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                            onClick = { onInvokeSource(source.id, source.requiredPermissions) },
                        ) {
                            Text(strings.invokeSource)
                        }
                    }
                    if (source.canAdd) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                            onClick = onAddLocalFile,
                        ) {
                            Text(strings.addLocalFile)
                        }
                    }
                    if (source.canIndex) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                            onClick = { onIndexSource(source.id) },
                        ) {
                            Text(strings.indexNow)
                        }
                    }
                    if (source.canIndex && source.supportsDateRangeIndexing) {
                        Text(strings.dateRangeIndexingTitle(), style = MaterialTheme.typography.titleSmall)
                        listOf(7, 30, 90).forEach { days ->
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !working,
                                onClick = { onIndexSourceDateRange(source.id, days) },
                            ) {
                                Text(strings.lastDaysLabel(days))
                            }
                        }
                    }
                    if (source.canRevoke) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                            onClick = { onRevokeSource(source.id) },
                        ) {
                            Text(
                                if (source.id == LocalFilesConnectorId) {
                                    strings.localDocumentRevokeAllAction()
                                } else {
                                    strings.revoke
                                },
                            )
                        }
                    }
                    if (source.canDelete) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !working,
                            onClick = { onDeleteSourceData(source.id) },
                        ) {
                            Text(strings.delete)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    appSecurityState: AppSecurityState,
    rows: List<String>,
    modelOptions: List<ModelOptionUiState>,
    ocrLanguagePacks: List<OcrLanguagePackUiState>,
    statusMessage: String,
    languageOption: GrayinLanguageOption,
    strings: GrayinStrings,
    working: Boolean,
    onScreenshotBlockingChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onLanguageSelected: (GrayinLanguageOption) -> Unit,
    onIndex: () -> Unit,
    onOpenModelDownload: (String?) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onCancelModelDownload: (String) -> Unit,
    onDeleteDownloadedModel: (String) -> Unit,
    onDownloadOcrLanguagePack: (String) -> Unit,
    onCancelOcrLanguagePackDownload: (String) -> Unit,
    onDeleteOcrLanguagePack: (String) -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(strings.settings, style = MaterialTheme.typography.headlineMedium)
        }
        item {
            LanguageSettings(
                languageOption = languageOption,
                strings = strings,
                onLanguageSelected = onLanguageSelected,
            )
        }
        item {
            AppSecuritySettings(
                state = appSecurityState,
                strings = strings,
                working = working,
                onScreenshotBlockingChanged = onScreenshotBlockingChanged,
                onAppLockChanged = onAppLockChanged,
                onOpenDeviceSecuritySettings = onOpenDeviceSecuritySettings,
            )
        }
        item {
            EncryptedBackupSettings(
                strings = strings,
                working = working,
                onExport = onExportBackup,
                onImport = onImportBackup,
            )
        }
        item {
            Button(
                enabled = !working,
                onClick = onIndex,
            ) {
                Text(if (working) strings.indexing else strings.indexNow)
            }
        }
        item {
            OcrLanguagePackSettings(
                packs = ocrLanguagePacks,
                strings = strings,
                working = working,
                onDownload = onDownloadOcrLanguagePack,
                onCancelDownload = onCancelOcrLanguagePackDownload,
                onDelete = onDeleteOcrLanguagePack,
            )
        }
        item {
            LocalModelSettings(
                modelOptions = modelOptions,
                strings = strings,
                working = working,
                onOpenModelDownload = onOpenModelDownload,
                onSelectModel = onSelectModel,
                onDownloadModel = onDownloadModel,
                onCancelModelDownload = onCancelModelDownload,
                onDeleteDownloadedModel = onDeleteDownloadedModel,
            )
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    enabled = !working,
                    onClick = { onOpenModelDownload(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text(strings.openLocalGemmaModelDownloadPage)
                }
                Button(
                    enabled = !working,
                    onClick = onImportModel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(strings.importLocalGemmaModel)
                }
                OutlinedButton(
                    enabled = !working,
                    onClick = onDeleteModel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = null)
                    Text(strings.deleteLocalGemmaModel)
                }
            }
        }
        if (statusMessage.isNotBlank()) {
            item {
                StatusRow(statusMessage)
            }
        }
        items(rows) { row ->
            StatusRow(row)
        }
    }
}

@Composable
private fun AppSecuritySettings(
    state: AppSecurityState,
    strings: GrayinStrings,
    working: Boolean,
    onScreenshotBlockingChanged: (Boolean) -> Unit,
    onAppLockChanged: (Boolean) -> Unit,
    onOpenDeviceSecuritySettings: () -> Unit,
) {
    val controlsEnabled = !working && state.activeAttempt == null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.appSecurityTitle(), style = MaterialTheme.typography.titleMedium)
            Text(strings.appSecurityDisclosure(), style = MaterialTheme.typography.bodySmall)
            SecurityToggleRow(
                label = strings.screenshotBlocking(),
                checked = state.preferences.screenshotBlockingEnabled,
                enabled = controlsEnabled,
                onCheckedChange = onScreenshotBlockingChanged,
            )
            SecurityToggleRow(
                label = strings.appLock(),
                checked = state.preferences.appLockEnabled,
                enabled = controlsEnabled,
                onCheckedChange = onAppLockChanged,
            )
            Text(strings.appLockStatus(state.lockState), style = MaterialTheme.typography.bodySmall)
            state.failure?.let { failure ->
                Text(
                    strings.appSecurityFailure(failure),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (failure.requiresDeviceSecuritySettings()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = controlsEnabled,
                        onClick = onOpenDeviceSecuritySettings,
                    ) {
                        Text(strings.openDeviceSecuritySettings())
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

private fun AppSecurityFailure?.requiresDeviceSecuritySettings(): Boolean {
    return this == AppSecurityFailure.AUTHENTICATION_LOCKED_OUT ||
        this == AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED ||
        this == AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE ||
        this == AppSecurityFailure.AUTHENTICATION_UNSUPPORTED ||
        this == AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED
}

@Composable
private fun EncryptedBackupSettings(
    strings: GrayinStrings,
    working: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.backupTitle(), style = MaterialTheme.typography.titleMedium)
            Text(strings.backupDisclosure(), style = MaterialTheme.typography.bodySmall)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                onClick = onExport,
            ) {
                Text(strings.exportEncryptedBackup())
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                onClick = onImport,
            ) {
                Text(strings.importEncryptedBackup())
            }
        }
    }
}

@Composable
private fun OcrLanguagePackSettings(
    packs: List<OcrLanguagePackUiState>,
    strings: GrayinStrings,
    working: Boolean,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.ocrLanguageDataTitle(), style = MaterialTheme.typography.titleMedium)
        Text(strings.ocrLanguageDataDisclosure(), style = MaterialTheme.typography.bodySmall)
        packs.forEach { pack ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall)
                    Text(pack.status, style = MaterialTheme.typography.bodyMedium)
                    pack.detailRows.forEach { row ->
                        Text(row, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pack.canDownload) {
                        Button(
                            enabled = !working,
                            onClick = { onDownload(pack.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.ocrLanguagePackDownloadAction())
                        }
                    }
                    if (pack.canCancelDownload) {
                        OutlinedButton(
                            enabled = !working,
                            onClick = { onCancelDownload(pack.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.ocrLanguagePackCancelAction())
                        }
                    }
                    if (pack.canDelete) {
                        OutlinedButton(
                            enabled = !working,
                            onClick = { onDelete(pack.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.ocrLanguagePackDeleteAction())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelSettings(
    modelOptions: List<ModelOptionUiState>,
    strings: GrayinStrings,
    working: Boolean,
    onOpenModelDownload: (String?) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onCancelModelDownload: (String) -> Unit,
    onDeleteDownloadedModel: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.localModelCatalogTitle, style = MaterialTheme.typography.titleMedium)
        modelOptions.forEach { option ->
            LocalModelOption(
                option = option,
                strings = strings,
                working = working,
                onOpenModelDownload = onOpenModelDownload,
                onSelectModel = onSelectModel,
                onDownloadModel = onDownloadModel,
                onCancelModelDownload = onCancelModelDownload,
                onDeleteDownloadedModel = onDeleteDownloadedModel,
            )
        }
    }
}

@Composable
private fun LocalModelOption(
    option: ModelOptionUiState,
    strings: GrayinStrings,
    working: Boolean,
    onOpenModelDownload: (String?) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onCancelModelDownload: (String) -> Unit,
    onDeleteDownloadedModel: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(option.name, style = MaterialTheme.typography.titleSmall)
                    Text(option.status, style = MaterialTheme.typography.bodyMedium)
                }
                if (option.selected) {
                    Text(strings.localModelSelectedBadge, style = MaterialTheme.typography.labelMedium)
                }
            }
            option.detailRows.forEach { row ->
                Text(row, style = MaterialTheme.typography.bodySmall)
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (option.canSelect) {
                    OutlinedButton(
                        enabled = !working,
                        onClick = { onSelectModel(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.localModelSelect)
                    }
                }
                if (option.downloadPageUrl != null) {
                    TextButton(
                        enabled = !working,
                        onClick = { onOpenModelDownload(option.downloadPageUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(strings.localModelOpenDownloadPage)
                    }
                }
                if (option.canDownload) {
                    Button(
                        enabled = !working,
                        onClick = { onDownloadModel(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.localModelDownload)
                    }
                }
                if (option.canCancelDownload) {
                    OutlinedButton(
                        enabled = !working,
                        onClick = { onCancelModelDownload(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.localModelCancelDownload)
                    }
                }
                if (option.canDeleteDownloaded) {
                    OutlinedButton(
                        enabled = !working,
                        onClick = { onDeleteDownloadedModel(option.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.localModelDeleteDownloaded)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleListScreen(
    title: String,
    rows: List<String>,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        items(rows) { row ->
            StatusRow(row)
        }
    }
}

@Composable
private fun LanguageSettings(
    languageOption: GrayinLanguageOption,
    strings: GrayinStrings,
    onLanguageSelected: (GrayinLanguageOption) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.language, style = MaterialTheme.typography.titleMedium)
            GrayinLanguageOption.entries.forEach { option ->
                if (option == languageOption) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onLanguageSelected(option) },
                    ) {
                        Text(strings.languageOptionLabel(option))
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onLanguageSelected(option) },
                    ) {
                        Text(strings.languageOptionLabel(option))
                    }
                }
            }
        }
    }
}

private fun GrayinScreen.icon(): ImageVector {
    return when (this) {
        GrayinScreen.Ask -> Icons.Filled.Search
        GrayinScreen.Timeline -> Icons.Filled.DateRange
        GrayinScreen.Places -> Icons.Filled.Place
        GrayinScreen.Sources -> Icons.Filled.Folder
        GrayinScreen.Settings -> Icons.Filled.Settings
    }
}

private const val CalendarConnectorId = "calendar"
private const val LocationConnectorId = "location"
private const val PhotosConnectorId = "photos"
private const val AppUsageConnectorId = "app_usage"
private const val NotificationConnectorId = "notification"
private const val LocalFilesConnectorId = "local_files"
private const val LocalGemmaModelDownloadPage = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
private const val StatusRefreshIntervalMs = 2_000L

internal object LocalDocumentPickerContract {
    fun mimeTypes(): Array<String> = arrayOf(
        "text/plain",
        "text/markdown",
        "application/pdf",
        "application/octet-stream",
    )
}

private fun emptySnapshot(strings: GrayinStrings): GrayinSnapshot {
    return GrayinSnapshot(
        sourceRows = emptyList(),
        indexingStatus = IndexingStatusUiState.empty(),
        timelineRows = listOf(strings.noDerivedEvents),
        placesRows = listOf(strings.noPlaceClusters),
        settingsRows = listOf(strings.loadingLocalState),
        modelOptions = emptyList(),
        ocrLanguagePacks = emptyList(),
    )
}

private fun emptyAnswerState(strings: GrayinStrings): AnswerUiState {
    return AnswerUiState(
        answer = strings.noAnswerAvailable,
        confidence = strings.confidenceLabel(ai.grayin.core.model.ConfidenceLevel.UNKNOWN),
        evidenceRows = listOf(strings.noCitedEvidence),
        missingRows = listOf(strings.addAndIndexLocalFileFirst),
    )
}

@Composable
private fun StatusRow(row: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = row,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

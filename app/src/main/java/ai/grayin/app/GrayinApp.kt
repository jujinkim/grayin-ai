package ai.grayin.app

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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class GrayinScreen {
    Ask,
    Timeline,
    Places,
    Sources,
    Settings,
}

@Composable
fun GrayinApp() {
    val context = LocalContext.current
    val controller = remember(context) { GrayinMemoryController(context) }
    val languageStore = remember(context) { LanguagePreferenceStore(context) }
    val scope = rememberCoroutineScope()
    var selectedScreenName by rememberSaveable { mutableStateOf(GrayinScreen.Ask.name) }
    var languageOptionName by rememberSaveable { mutableStateOf(languageStore.load().name) }
    val languageOption = GrayinLanguageOption.valueOf(languageOptionName)
    val strings = remember(languageOption) { GrayinText.forOption(languageOption) }
    var snapshot by remember { mutableStateOf(emptySnapshot(strings)) }
    var answerState by remember { mutableStateOf(emptyAnswerState(strings)) }
    var statusMessage by remember { mutableStateOf("") }
    var hasAsked by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val selectedScreen = GrayinScreen.valueOf(selectedScreenName)
    val screens = GrayinScreen.entries
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                try {
                    statusMessage = controller.rememberSelectedLocalFile(uri, strings)
                } catch (error: Throwable) {
                    statusMessage = error.message ?: strings.localFileSelectionFailed
                } finally {
                    snapshot = controller.snapshot(strings)
                    working = false
                }
            }
        }
    }

    fun refreshSnapshot() {
        scope.launch {
            snapshot = controller.snapshot(strings)
        }
    }

    LaunchedEffect(controller, strings) {
        snapshot = controller.snapshot(strings)
        if (!hasAsked) {
            answerState = emptyAnswerState(strings)
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
                                } catch (error: Throwable) {
                                    answerState = AnswerUiState(
                                        answer = error.message ?: strings.searchFailed,
                                        confidence = "UNKNOWN",
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
                        strings = strings,
                        working = working,
                        onAddLocalFile = {
                            openDocumentLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                        },
                        onRevokeLocalFiles = {
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.revokeLocalFiles(strings)
                                } catch (error: Throwable) {
                                    statusMessage = error.message ?: strings.revokeFailed
                                } finally {
                                    snapshot = controller.snapshot(strings)
                                    working = false
                                }
                            }
                        },
                        onDeleteLocalFileData = {
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.deleteLocalFileData(strings)
                                } catch (error: Throwable) {
                                    statusMessage = error.message ?: strings.deleteFailed
                                } finally {
                                    snapshot = controller.snapshot(strings)
                                    working = false
                                }
                            }
                        },
                    )
                    GrayinScreen.Settings -> SettingsScreen(
                        rows = snapshot.settingsRows,
                        statusMessage = statusMessage,
                        languageOption = languageOption,
                        strings = strings,
                        working = working,
                        onLanguageSelected = { option ->
                            languageStore.save(option)
                            languageOptionName = option.name
                            statusMessage = ""
                        },
                        onIndex = {
                            scope.launch {
                                working = true
                                try {
                                    statusMessage = controller.indexLocalFiles(strings)
                                } catch (error: Throwable) {
                                    statusMessage = error.message ?: strings.indexingFailed
                                } finally {
                                    snapshot = controller.snapshot(strings)
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

@Composable
private fun AskScreen(
    answerState: AnswerUiState,
    strings: GrayinStrings,
    working: Boolean,
    onAsk: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

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
    strings: GrayinStrings,
    working: Boolean,
    onAddLocalFile: () -> Unit,
    onRevokeLocalFiles: () -> Unit,
    onDeleteLocalFileData: () -> Unit,
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
            Button(
                enabled = !working,
                onClick = onAddLocalFile,
            ) {
                Text(strings.addLocalFile)
            }
        }
        items(sourceRows) { source ->
            SourceRow(
                source = source,
                strings = strings,
                working = working,
                onRevokeLocalFiles = onRevokeLocalFiles,
                onDeleteLocalFileData = onDeleteLocalFileData,
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: ConnectorUiState,
    strings: GrayinStrings,
    working: Boolean,
    onRevokeLocalFiles: () -> Unit,
    onDeleteLocalFileData: () -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = source.canRevoke && !working,
                    onClick = onRevokeLocalFiles,
                ) {
                    Text(strings.revoke)
                }
                OutlinedButton(
                    enabled = source.canDelete && !working,
                    onClick = onDeleteLocalFileData,
                ) {
                    Text(strings.delete)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    rows: List<String>,
    statusMessage: String,
    languageOption: GrayinLanguageOption,
    strings: GrayinStrings,
    working: Boolean,
    onLanguageSelected: (GrayinLanguageOption) -> Unit,
    onIndex: () -> Unit,
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
            Button(
                enabled = !working,
                onClick = onIndex,
            ) {
                Text(if (working) strings.indexing else strings.indexNow)
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

private fun emptySnapshot(strings: GrayinStrings): GrayinSnapshot {
    return GrayinSnapshot(
        sourceRows = emptyList(),
        timelineRows = listOf(strings.noDerivedEvents),
        placesRows = listOf(strings.noPlaceClusters),
        settingsRows = listOf(strings.loadingLocalState),
    )
}

private fun emptyAnswerState(strings: GrayinStrings): AnswerUiState {
    return AnswerUiState(
        answer = strings.noAnswerAvailable,
        confidence = "UNKNOWN",
        evidenceRows = listOf(strings.noCitedEvidence),
        missingRows = listOf(strings.addAndIndexLocalFileFirst),
    )
}

@Composable
private fun StatusRow(row: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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

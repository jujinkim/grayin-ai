package ai.grayin.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class GrayinScreen(val label: String) {
    Ask("Ask"),
    Timeline("Timeline"),
    Places("Places"),
    Sources("Sources"),
    Settings("Settings"),
}

@Composable
fun GrayinApp() {
    val context = LocalContext.current
    val controller = remember(context) { GrayinMemoryController(context) }
    val scope = rememberCoroutineScope()
    var selectedScreenName by rememberSaveable { mutableStateOf(GrayinScreen.Ask.name) }
    var snapshot by remember { mutableStateOf(emptySnapshot()) }
    var answerState by remember { mutableStateOf(emptyAnswerState()) }
    var statusMessage by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val selectedScreen = GrayinScreen.valueOf(selectedScreenName)
    val screens = GrayinScreen.entries

    fun refreshSnapshot() {
        scope.launch {
            snapshot = controller.snapshot()
        }
    }

    LaunchedEffect(controller) {
        snapshot = controller.snapshot()
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
                            icon = {},
                            label = { Text(screen.label) },
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
                        working = working,
                        onAsk = { query ->
                            scope.launch {
                                working = true
                                answerState = controller.ask(query)
                                working = false
                                refreshSnapshot()
                            }
                        },
                    )

                    GrayinScreen.Timeline -> TimelineScreen(snapshot.timelineRows)
                    GrayinScreen.Places -> PlacesScreen(snapshot.placesRows)
                    GrayinScreen.Sources -> SourcesScreen(snapshot.sourceRows)
                    GrayinScreen.Settings -> SettingsScreen(
                        rows = snapshot.settingsRows,
                        statusMessage = statusMessage,
                        working = working,
                        onIndex = {
                            scope.launch {
                                working = true
                                statusMessage = controller.indexLocalFiles()
                                snapshot = controller.snapshot()
                                working = false
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
            Text("Ask", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text("Memory question") },
                singleLine = false,
                minLines = 3,
            )
        }
        item {
            Button(
                enabled = query.isNotBlank() && !working,
                onClick = { onAsk(query) },
            ) {
                Text(if (working) "Searching" else "Search")
            }
        }
        item {
            AnswerCard(
                answer = answerState.answer,
                confidence = answerState.confidence,
                evidenceRows = answerState.evidenceRows,
                missingRows = answerState.missingRows,
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
            Text("Answer", style = MaterialTheme.typography.titleMedium)
            Text(answer, style = MaterialTheme.typography.bodyLarge)
            ConfidenceLabel(confidence)
            HorizontalDivider()
            EvidenceSection(evidenceRows)
            HorizontalDivider()
            MissingDataSection(missingRows)
        }
    }
}

@Composable
private fun ConfidenceLabel(confidence: String) {
    Text(
        text = "Confidence: $confidence",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EvidenceSection(rows: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Evidence", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            rows.forEach { row ->
                Text("- $row", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text("${rows.size} item", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MissingDataSection(rows: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Missing data", style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            Text("- $row", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TimelineScreen(rows: List<String>) {
    SimpleListScreen(
        title = "Timeline",
        rows = rows,
    )
}

@Composable
private fun PlacesScreen(rows: List<String>) {
    SimpleListScreen(
        title = "Places",
        rows = rows,
    )
}

@Composable
private fun SourcesScreen(sourceRows: List<ConnectorUiState>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Sources", style = MaterialTheme.typography.headlineMedium)
        }
        items(sourceRows) { source ->
            SourceRow(source)
        }
    }
}

@Composable
private fun SourceRow(source: ConnectorUiState) {
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
                    enabled = false,
                    onClick = {},
                ) {
                    Text("Revoke")
                }
                OutlinedButton(
                    enabled = false,
                    onClick = {},
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    rows: List<String>,
    statusMessage: String,
    working: Boolean,
    onIndex: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Button(
                enabled = !working,
                onClick = onIndex,
            ) {
                Text(if (working) "Indexing" else "Index now")
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

private fun emptySnapshot(): GrayinSnapshot {
    return GrayinSnapshot(
        sourceRows = emptyList(),
        timelineRows = listOf("No derived memory events indexed."),
        placesRows = listOf("No place clusters indexed."),
        settingsRows = listOf("Loading local state."),
    )
}

private fun emptyAnswerState(): AnswerUiState {
    return AnswerUiState(
        answer = "No answer available from indexed evidence.",
        confidence = "UNKNOWN",
        evidenceRows = listOf("No cited evidence available."),
        missingRows = listOf("Add and index a local text or Markdown file first."),
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

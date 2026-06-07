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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private enum class GrayinScreen(val label: String) {
    Ask("Ask"),
    Timeline("Timeline"),
    Places("Places"),
    Sources("Sources"),
    Settings("Settings"),
}

private data class ConnectorUiState(
    val name: String,
    val status: String,
    val sensitivity: String,
)

private val sourceRows = listOf(
    ConnectorUiState("Location", "Off", "High sensitivity"),
    ConnectorUiState("Photos", "Off", "High sensitivity"),
    ConnectorUiState("Calendar", "Off", "High sensitivity"),
    ConnectorUiState("Notifications", "Off", "Very high sensitivity"),
    ConnectorUiState("App usage", "Off", "Very high sensitivity"),
    ConnectorUiState("Local files", "Off", "High sensitivity"),
)

@Composable
fun GrayinApp() {
    var selectedScreenName by rememberSaveable { mutableStateOf(GrayinScreen.Ask.name) }
    val selectedScreen = GrayinScreen.valueOf(selectedScreenName)
    val screens = GrayinScreen.entries

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
                    GrayinScreen.Ask -> AskScreen()
                    GrayinScreen.Timeline -> TimelineScreen()
                    GrayinScreen.Places -> PlacesScreen()
                    GrayinScreen.Sources -> SourcesScreen()
                    GrayinScreen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun AskScreen() {
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
                enabled = false,
                onClick = {},
            ) {
                Text("Search")
            }
        }
        item {
            AnswerCard(
                answer = "No answer available from indexed evidence.",
                confidence = "Unknown",
                evidenceRows = listOf("No cited evidence available."),
                missingRows = listOf(
                    "Location not indexed.",
                    "Calendar not indexed.",
                    "Photos not indexed.",
                    "Notifications not indexed.",
                    "App usage not indexed.",
                    "Local files not indexed.",
                ),
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
private fun TimelineScreen() {
    SimpleListScreen(
        title = "Timeline",
        rows = listOf("No derived memory events indexed."),
    )
}

@Composable
private fun PlacesScreen() {
    SimpleListScreen(
        title = "Places",
        rows = listOf("No place clusters indexed."),
    )
}

@Composable
private fun SourcesScreen() {
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
private fun SettingsScreen() {
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
                enabled = false,
                onClick = {},
            ) {
                Text("Index now")
            }
        }
        items(
            listOf(
                "Network permission: absent",
                "Account: absent",
                "Cloud sync: absent",
                "Telemetry: absent",
                "Crash analytics: absent",
            ),
        ) { row ->
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


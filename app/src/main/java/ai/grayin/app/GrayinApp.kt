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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ask", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = query,
            onValueChange = { query = it },
            label = { Text("Memory question") },
            singleLine = false,
            minLines = 3,
        )
        Button(
            enabled = false,
            onClick = {},
        ) {
            Text("Search")
        }
        EvidenceState(
            title = "No indexed evidence",
            body = "Answer unavailable until at least one source is enabled and indexed.",
            confidence = "Confidence: low",
            missing = "Missing data: location, calendar, photos, notifications, app usage, local files.",
        )
    }
}

@Composable
private fun TimelineScreen() {
    PlaceholderScreen(
        title = "Timeline",
        rows = listOf("No derived memory events indexed."),
    )
}

@Composable
private fun PlacesScreen() {
    PlaceholderScreen(
        title = "Places",
        rows = listOf("No place clusters indexed."),
    )
}

@Composable
private fun SourcesScreen() {
    PlaceholderScreen(
        title = "Sources",
        rows = listOf(
            "Location: off",
            "Photos: off",
            "Calendar: off",
            "Notifications: off",
            "App usage: off",
            "Local files: off",
        ),
    )
}

@Composable
private fun SettingsScreen() {
    PlaceholderScreen(
        title = "Settings",
        rows = listOf(
            "Network permission: absent",
            "Account: absent",
            "Cloud sync: absent",
            "Telemetry: absent",
        ),
    )
}

@Composable
private fun PlaceholderScreen(
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
    }
}

@Composable
private fun EvidenceState(
    title: String,
    body: String,
    confidence: String,
    missing: String,
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
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(confidence, style = MaterialTheme.typography.bodySmall)
            }
            Text(missing, style = MaterialTheme.typography.bodySmall)
        }
    }
}


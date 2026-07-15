package ai.grayin.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class IndexingStatusBlockInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyConciseDynamicSummaryIsALiveRegion() {
        val strings = GrayinText.forOption(GrayinLanguageOption.ENGLISH)
        val status = IndexingStatusUiState.empty().copy(
            queueDepth = 3,
            runningSourceNames = listOf("Calendar", "Photos"),
        )
        composeRule.setContent {
            MaterialTheme {
                IndexingStatusBlock(status = status, strings = strings)
            }
        }

        composeRule.onNodeWithText(
            "Indexing: 3 queued, 2 running. Automatic: Not run",
        ).assertExists()
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }
}

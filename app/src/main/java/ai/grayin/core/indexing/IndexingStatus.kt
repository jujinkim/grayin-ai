package ai.grayin.core.indexing

import ai.grayin.core.model.ProcessingState
import java.time.Instant

data class IndexingStatus(
    val queueDepth: Int,
    val runningConnectorIds: Set<String>,
    val lastCompletedAt: Instant? = null,
    val automaticPolicy: AutomaticIndexingPolicy,
    val manualCommandsAvailable: Set<ManualIndexingCommand>,
    val items: List<IndexingStatusItem> = emptyList(),
)

data class IndexingStatusItem(
    val itemId: String,
    val connectorId: String? = null,
    val commandLabel: String,
    val state: ProcessingState,
    val requestedAt: Instant,
    val completedAt: Instant? = null,
    val statusExplanation: String? = null,
)

enum class ManualIndexingCommand {
    INDEX_NOW,
    INDEX_CONNECTOR,
    INDEX_DATE_RANGE,
}


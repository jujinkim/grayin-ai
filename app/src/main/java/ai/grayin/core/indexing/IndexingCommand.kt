package ai.grayin.core.indexing

import java.time.Instant

sealed interface IndexingCommand {
    val connectorId: String?
}

data object IndexNow : IndexingCommand {
    override val connectorId: String? = null
}

data class IndexConnector(
    override val connectorId: String,
) : IndexingCommand

data class IndexDateRange(
    val from: Instant,
    val until: Instant,
    override val connectorId: String? = null,
) : IndexingCommand


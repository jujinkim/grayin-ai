package ai.grayin.core.connector

import java.time.Instant

data class ConnectorScanScope(
    val from: Instant? = null,
    val until: Instant? = null,
    val sourceReferenceIds: List<String> = emptyList(),
    val forceRefresh: Boolean = false,
)

data class ConnectorDeleteRequest(
    val connectorId: String,
    val deleteSourceReferences: Boolean = true,
    val deleteDerivedMemory: Boolean = true,
    val invalidateIndexes: Boolean = true,
)


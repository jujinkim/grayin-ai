package ai.grayin.core.connector

import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

enum class ConnectorIndexingMode {
    BACKGROUND_SCANNABLE,
    FOREGROUND_ONLY,
    EVENT_DRIVEN,
}

data class ConnectorMetadata(
    val connectorId: String,
    val displayName: String,
    val sourceKinds: Set<SourceKind>,
    val connectorCapabilities: Set<ConnectorCapability>,
    val memoryCapabilities: Set<MemoryCapability>,
    val indexingMode: ConnectorIndexingMode,
    val defaultEnabled: Boolean = false,
    val sensitivity: SensitivityLevel,
)

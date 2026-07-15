package ai.grayin.core.model

import java.time.Instant

data class ConnectorState(
    val connectorId: String,
    val displayName: String,
    val enabled: Boolean,
    val consentEnabled: Boolean = enabled,
    val availability: SourceAvailability,
    val permissionGranted: Boolean,
    val capabilities: Set<ConnectorCapability>,
    val sensitivity: SensitivityLevel,
    val processingState: ProcessingState,
    val lastIndexedAt: Instant? = null,
)

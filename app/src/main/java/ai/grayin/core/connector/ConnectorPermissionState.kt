package ai.grayin.core.connector

import ai.grayin.core.model.SourceAvailability

data class ConnectorPermissionState(
    val connectorId: String,
    val availability: SourceAvailability,
    val permissionGranted: Boolean,
    val canRequestPermission: Boolean,
    val requiredPlatformPermissions: List<String> = emptyList(),
    val explanation: String? = null,
)


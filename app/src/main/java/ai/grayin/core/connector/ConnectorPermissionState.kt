package ai.grayin.core.connector

import ai.grayin.core.model.SourceAvailability

enum class ConnectorPermissionAccess {
    NONE,
    PARTIAL,
    FULL,
}

data class ConnectorPermissionState(
    val connectorId: String,
    val availability: SourceAvailability,
    val permissionGranted: Boolean,
    val canRequestPermission: Boolean,
    val requiredPlatformPermissions: List<String> = emptyList(),
    val explanation: String? = null,
    val access: ConnectorPermissionAccess = if (permissionGranted) {
        ConnectorPermissionAccess.FULL
    } else {
        ConnectorPermissionAccess.NONE
    },
)

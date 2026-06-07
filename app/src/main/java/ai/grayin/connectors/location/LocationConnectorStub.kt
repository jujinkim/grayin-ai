package ai.grayin.connectors.location

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class LocationConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "location",
        displayName = "Location",
        sourceKinds = setOf(SourceKind.LOCATION),
        connectorCapabilities = setOf(ConnectorCapability.LOCATION),
        memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.HIGH,
    ),
    requiredPlatformPermissions = listOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    ),
)


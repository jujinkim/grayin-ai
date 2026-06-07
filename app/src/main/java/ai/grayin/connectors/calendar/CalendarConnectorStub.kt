package ai.grayin.connectors.calendar

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class CalendarConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "calendar",
        displayName = "Calendar",
        sourceKinds = setOf(SourceKind.CALENDAR),
        connectorCapabilities = setOf(ConnectorCapability.CALENDAR),
        memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.HIGH,
    ),
    requiredPlatformPermissions = listOf("android.permission.READ_CALENDAR"),
)


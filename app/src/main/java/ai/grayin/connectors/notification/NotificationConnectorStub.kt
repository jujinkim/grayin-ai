package ai.grayin.connectors.notification

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class NotificationConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "notification",
        displayName = "Notifications",
        sourceKinds = setOf(SourceKind.NOTIFICATION),
        connectorCapabilities = setOf(ConnectorCapability.NOTIFICATIONS),
        memoryCapabilities = setOf(
            MemoryCapability.HAS_TIME,
            MemoryCapability.HAS_PAYMENT,
            MemoryCapability.HAS_DELIVERY,
            MemoryCapability.HAS_RESERVATION,
            MemoryCapability.HAS_TRANSPORT,
            MemoryCapability.HAS_TEXT,
        ),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.VERY_HIGH,
    ),
    requiredPlatformPermissions = listOf("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
)


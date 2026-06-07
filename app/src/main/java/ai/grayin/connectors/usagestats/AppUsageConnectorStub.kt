package ai.grayin.connectors.usagestats

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class AppUsageConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "app_usage",
        displayName = "App Usage",
        sourceKinds = setOf(SourceKind.APP_USAGE),
        connectorCapabilities = setOf(ConnectorCapability.APP_USAGE),
        memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_APP_USAGE),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.VERY_HIGH,
    ),
    requiredPlatformPermissions = listOf("android.permission.PACKAGE_USAGE_STATS"),
)


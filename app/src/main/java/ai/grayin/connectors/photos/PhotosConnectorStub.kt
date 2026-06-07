package ai.grayin.connectors.photos

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class PhotosConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "photos",
        displayName = "Photos",
        sourceKinds = setOf(SourceKind.PHOTO),
        connectorCapabilities = setOf(ConnectorCapability.PHOTOS),
        memoryCapabilities = setOf(
            MemoryCapability.HAS_TIME,
            MemoryCapability.HAS_MEDIA,
            MemoryCapability.HAS_VISUAL_LABEL,
            MemoryCapability.HAS_LOCATION,
        ),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.HIGH,
    ),
    requiredPlatformPermissions = listOf("android.permission.READ_MEDIA_IMAGES"),
)


package ai.grayin.connectors.localfiles

import ai.grayin.connectors.StubMemoryConnector
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind

class LocalFilesConnectorStub : StubMemoryConnector(
    metadata = ConnectorMetadata(
        connectorId = "local_files",
        displayName = "Local Files",
        sourceKinds = setOf(
            SourceKind.LOCAL_FILE,
            SourceKind.MARKDOWN_NOTE,
            SourceKind.PDF_PAGE,
            SourceKind.OCR_TEXT,
        ),
        connectorCapabilities = setOf(ConnectorCapability.LOCAL_FILES),
        memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT),
        defaultEnabled = false,
        sensitivity = SensitivityLevel.HIGH,
    ),
    requiredPlatformPermissions = emptyList(),
)


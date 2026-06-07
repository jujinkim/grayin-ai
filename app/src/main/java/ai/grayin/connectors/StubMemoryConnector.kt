package ai.grayin.connectors

import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceAvailability
import java.time.Instant

abstract class StubMemoryConnector(
    final override val metadata: ConnectorMetadata,
    private val requiredPlatformPermissions: List<String>,
) : MemoryConnector {
    final override suspend fun currentState(): ConnectorState {
        return ConnectorState(
            connectorId = metadata.connectorId,
            displayName = metadata.displayName,
            enabled = false,
            availability = SourceAvailability.DISABLED,
            permissionGranted = false,
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = ProcessingState.SKIPPED,
        )
    }

    final override suspend fun permissionState(): ConnectorPermissionState {
        return ConnectorPermissionState(
            connectorId = metadata.connectorId,
            availability = SourceAvailability.DISABLED,
            permissionGranted = false,
            canRequestPermission = false,
            requiredPlatformPermissions = requiredPlatformPermissions,
            explanation = "Platform permission implementation is pending for this connector.",
        )
    }

    final override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = metadata.connectorId,
            processingState = ProcessingState.SKIPPED,
            missingSources = metadata.memoryCapabilities.map { capability ->
                MissingSource(
                    capability = capability,
                    availability = SourceAvailability.NOT_INDEXED,
                    explanation = "Connector stub has no platform implementation yet.",
                    connectorId = metadata.connectorId,
                )
            },
            scannedAt = Instant.now(),
        )
    }

    final override suspend fun revoke(): ConnectorRevokeResult {
        val revokedAt = Instant.now()
        return ConnectorRevokeResult(
            connectorId = metadata.connectorId,
            revokedAt = revokedAt,
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = metadata.connectorId),
        )
    }

    final override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }
}


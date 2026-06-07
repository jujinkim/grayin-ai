package ai.grayin.core.connector

import ai.grayin.core.model.ConnectorState

interface MemoryConnector {
    val metadata: ConnectorMetadata

    suspend fun currentState(): ConnectorState

    suspend fun permissionState(): ConnectorPermissionState

    suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult

    suspend fun revoke(): ConnectorRevokeResult

    suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult
}


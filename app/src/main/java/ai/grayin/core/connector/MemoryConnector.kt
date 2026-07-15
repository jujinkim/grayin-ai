package ai.grayin.core.connector

import ai.grayin.core.model.ConnectorState

interface MemoryConnector {
    val metadata: ConnectorMetadata

    suspend fun currentState(): ConnectorState

    suspend fun permissionState(): ConnectorPermissionState

    suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult

    /** Called only after the scan's derived output has committed to the local store. */
    suspend fun onScanStored(scanResult: ConnectorScanResult) = Unit

    suspend fun revoke(): ConnectorRevokeResult

    suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult
}

interface InvokableMemoryConnector : MemoryConnector {
    suspend fun invoke(): ConnectorPermissionState
}

package ai.grayin.core.connector

import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceReference
import java.time.Instant

data class ConnectorScanResult(
    val connectorId: String,
    val processingState: ProcessingState,
    val sourceReferences: List<SourceReference> = emptyList(),
    val derivedEvents: List<DerivedMemoryEvent> = emptyList(),
    val citations: List<MemoryCitation> = emptyList(),
    val placeClusters: List<PlaceCluster> = emptyList(),
    val appUsageSummaries: List<AppUsageSummary> = emptyList(),
    val missingSources: List<MissingSource> = emptyList(),
    val scopeFrom: Instant? = null,
    val scopeUntil: Instant? = null,
    val replaceExistingConnectorData: Boolean = false,
    val scannedAt: Instant,
) {
    init {
        require(scopeFrom == null || scopeUntil == null || scopeFrom.isBefore(scopeUntil)) {
            "Connector scan scope start must precede its end."
        }
    }
}

data class ConnectorScanStatus(
    val connectorId: String,
    val processingState: ProcessingState,
    val missingSources: List<MissingSource>,
    val scopeFrom: Instant? = null,
    val scopeUntil: Instant? = null,
    val scannedAt: Instant,
) {
    init {
        require(connectorId.isNotBlank()) { "Connector scan status must have a connector ID." }
        require(scopeFrom == null || scopeUntil == null || scopeFrom.isBefore(scopeUntil)) {
            "Connector scan status scope start must precede its end."
        }
        require(missingSources.all { it.connectorId == null || it.connectorId == connectorId }) {
            "Connector scan status cannot contain another connector's missing source."
        }
        require(
            missingSources.all { missing ->
                missing.issueCode != null && missing.explanation == missing.issueCode.defaultEnglish
            },
        ) {
            "Connector scan status must contain only stable issue codes."
        }
        require(missingSources.size <= MAX_MISSING_SOURCES) {
            "Connector scan status contains too many missing-source records."
        }
        require(missingSources.distinctBy(::missingSourceIdentity).size == missingSources.size) {
            "Connector scan status contains duplicate missing-source records."
        }
    }

    companion object {
        const val MAX_MISSING_SOURCES = 64
    }
}

internal fun missingSourceIdentity(missing: MissingSource): List<String> {
    return listOf(
        missing.capability.name,
        missing.availability.name,
        missing.issueCode?.storageKey.orEmpty(),
        missing.connectorId.orEmpty(),
    )
}

fun ConnectorScanResult.hasIndexableOutput(): Boolean {
    return sourceReferences.isNotEmpty() ||
        derivedEvents.isNotEmpty() ||
        citations.isNotEmpty() ||
        placeClusters.isNotEmpty() ||
        appUsageSummaries.isNotEmpty()
}

data class ConnectorRevokeResult(
    val connectorId: String,
    val revokedAt: Instant,
    val permissionState: ConnectorPermissionState,
    val deleteRequest: ConnectorDeleteRequest,
)

data class ConnectorDeleteResult(
    val connectorId: String,
    val deletedSourceReferenceIds: List<String> = emptyList(),
    val deletedDerivedMemoryEventIds: List<String> = emptyList(),
    val invalidatedIndexIds: List<String> = emptyList(),
    val completedAt: Instant,
)

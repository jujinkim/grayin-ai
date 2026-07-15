package ai.grayin.core.transfer

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageCategory
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.store.LocalMemorySnapshot
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GrayinTransferPayloadCodec {
    fun encode(payload: TransferPayload): TransferResult<ByteArray> {
        return try {
            val detachedPayload = payload.copy(
                snapshot = TransferSnapshotValidator.detached(payload.snapshot),
            )
            TransferSnapshotValidator.validate(detachedPayload)
            val encoded = JSON.encodeToString(detachedPayload.toDto()).toByteArray(Charsets.UTF_8)
            if (encoded.size > TransferBounds.MAX_PLAINTEXT_BYTES) {
                transferFailure(TransferFailureCode.TOO_LARGE)
            } else {
                TransferResult.Success(encoded)
            }
        } catch (_: RuntimeException) {
            transferFailure(TransferFailureCode.INVALID_PAYLOAD)
        }
    }

    fun decode(encoded: ByteArray): TransferResult<TransferPayload> {
        if (encoded.isEmpty()) return transferFailure(TransferFailureCode.INVALID_PAYLOAD)
        if (encoded.size > TransferBounds.MAX_PLAINTEXT_BYTES) {
            return transferFailure(TransferFailureCode.TOO_LARGE)
        }
        return try {
            val text = decodeStrictUtf8(encoded)
            StrictJsonPreflight.validate(text)
            val dto = JSON.decodeFromString<TransferPayloadDto>(text)
            require(dto.payloadVersion == PAYLOAD_VERSION)
            val payload = dto.toDomain()
            TransferSnapshotValidator.validate(payload)
            TransferResult.Success(payload)
        } catch (_: CharacterCodingException) {
            transferFailure(TransferFailureCode.INVALID_PAYLOAD)
        } catch (_: RuntimeException) {
            transferFailure(TransferFailureCode.INVALID_PAYLOAD)
        }
    }

    private fun decodeStrictUtf8(encoded: ByteArray): String {
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private companion object {
        const val PAYLOAD_VERSION = 1
        val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            allowStructuredMapKeys = false
            prettyPrint = false
        }
    }
}

@Serializable
private data class TransferPayloadDto(
    val payloadVersion: Int,
    val createdAt: String,
    val producer: TransferProducerMetadataDto,
    val sourceReferences: List<SourceReferenceDto>,
    val derivedMemoryEvents: List<DerivedMemoryEventDto>,
    val citations: List<MemoryCitationDto>,
    val dailySummaries: List<DailyMemorySummaryDto>,
    val placeClusters: List<PlaceClusterDto>,
    val appUsageSummaries: List<AppUsageSummaryDto>,
    val connectorScanStatuses: List<ConnectorScanStatusDto>,
)

@Serializable
private data class TransferProducerMetadataDto(
    val applicationId: String,
    val versionCode: Long,
    val storeSchemaVersion: Int,
)

@Serializable
private data class SourceReferenceDto(
    val id: String,
    val connectorId: String,
    val sourceKind: String,
    val externalIdHash: String?,
    val hmacHash: String?,
    val sourceAppIdentifier: String?,
    val observedAt: String?,
    val modifiedAt: String?,
    val sensitivity: String,
)

@Serializable
private data class DerivedMemoryEventDto(
    val id: String,
    val kind: String,
    val sourceReferenceIds: List<String>,
    val summary: String,
    val startedAt: String?,
    val endedAt: String?,
    val keywords: List<String>,
    val labels: List<String>,
    val entities: List<String>,
    val confidence: String,
    val sensitivity: String,
    val citationIds: List<String>,
    val createdAt: String,
)

@Serializable
private data class MemoryCitationDto(
    val id: String,
    val sourceReferenceId: String,
    val derivedMemoryEventId: String?,
    val label: String,
    val observedAt: String?,
    val confidence: String,
)

@Serializable
private data class DailyMemorySummaryDto(
    val id: String,
    val date: String,
    val summary: String,
    val derivedMemoryEventIds: List<String>,
    val placeClusterIds: List<String>,
    val appUsageSummaryIds: List<String>,
    val confidence: String,
    val missingSources: List<MissingSourceDto>,
)

@Serializable
private data class PlaceClusterDto(
    val id: String,
    val label: String?,
    val regionLabel: String?,
    val centroidLatitude: Double?,
    val centroidLongitude: Double?,
    val radiusMeters: Double?,
    val firstSeenAt: String?,
    val lastSeenAt: String?,
    val visitCount: Int,
    val sourceReferenceIds: List<String>,
    val confidence: String,
)

@Serializable
private data class AppUsageSummaryDto(
    val id: String,
    val sourceReferenceIds: List<String>,
    val date: String,
    val packageName: String,
    val appAlias: String?,
    val category: String,
    val totalDurationMinutes: Long,
    val launchCount: Int?,
    val activeTimeBucketLabels: List<String>,
    val confidence: String,
)

@Serializable
private data class ConnectorScanStatusDto(
    val connectorId: String,
    val processingState: String,
    val missingSources: List<MissingSourceDto>,
    val scopeFrom: String?,
    val scopeUntil: String?,
    val scannedAt: String,
)

@Serializable
private data class MissingSourceDto(
    val capability: String,
    val availability: String,
    val explanation: String,
    val connectorId: String?,
    val issueCode: String?,
)

private fun TransferPayload.toDto(): TransferPayloadDto {
    return TransferPayloadDto(
        payloadVersion = 1,
        createdAt = createdAt.toString(),
        producer = TransferProducerMetadataDto(
            applicationId = producer.applicationId,
            versionCode = producer.versionCode,
            storeSchemaVersion = producer.storeSchemaVersion,
        ),
        sourceReferences = snapshot.sourceReferences.map(SourceReference::toDto),
        derivedMemoryEvents = snapshot.derivedMemoryEvents.map(DerivedMemoryEvent::toDto),
        citations = snapshot.citations.map(MemoryCitation::toDto),
        dailySummaries = snapshot.dailySummaries.map(DailyMemorySummary::toDto),
        placeClusters = snapshot.placeClusters.map(PlaceCluster::toDto),
        appUsageSummaries = snapshot.appUsageSummaries.map(AppUsageSummary::toDto),
        connectorScanStatuses = snapshot.connectorScanStatuses.map(ConnectorScanStatus::toDto),
    )
}

private fun TransferPayloadDto.toDomain(): TransferPayload {
    return TransferPayload(
        createdAt = Instant.parse(createdAt),
        producer = TransferProducerMetadata(
            applicationId = producer.applicationId,
            versionCode = producer.versionCode,
            storeSchemaVersion = producer.storeSchemaVersion,
        ),
        snapshot = LocalMemorySnapshot(
            sourceReferences = sourceReferences.map(SourceReferenceDto::toDomain),
            derivedMemoryEvents = derivedMemoryEvents.map(DerivedMemoryEventDto::toDomain),
            citations = citations.map(MemoryCitationDto::toDomain),
            dailySummaries = dailySummaries.map(DailyMemorySummaryDto::toDomain),
            placeClusters = placeClusters.map(PlaceClusterDto::toDomain),
            appUsageSummaries = appUsageSummaries.map(AppUsageSummaryDto::toDomain),
            connectorScanStatuses = connectorScanStatuses.map(ConnectorScanStatusDto::toDomain),
        ),
    )
}

private fun SourceReference.toDto(): SourceReferenceDto {
    return SourceReferenceDto(
        id = id,
        connectorId = connectorId,
        sourceKind = sourceKind.name,
        externalIdHash = externalIdHash,
        hmacHash = hmacHash,
        sourceAppIdentifier = sourceAppIdentifier,
        observedAt = observedAt?.toString(),
        modifiedAt = modifiedAt?.toString(),
        sensitivity = sensitivity.name,
    )
}

private fun SourceReferenceDto.toDomain(): SourceReference {
    return SourceReference(
        id = id,
        connectorId = connectorId,
        sourceKind = enumValueOf(sourceKind),
        localPointer = null,
        externalIdHash = externalIdHash,
        hmacHash = hmacHash,
        sourceAppIdentifier = sourceAppIdentifier,
        observedAt = observedAt?.let(Instant::parse),
        modifiedAt = modifiedAt?.let(Instant::parse),
        sensitivity = enumValueOf(sensitivity),
    )
}

private fun DerivedMemoryEvent.toDto(): DerivedMemoryEventDto {
    return DerivedMemoryEventDto(
        id = id,
        kind = kind.name,
        sourceReferenceIds = sourceReferenceIds,
        summary = summary,
        startedAt = startedAt?.toString(),
        endedAt = endedAt?.toString(),
        keywords = keywords,
        labels = labels,
        entities = entities,
        confidence = confidence.name,
        sensitivity = sensitivity.name,
        citationIds = citationIds,
        createdAt = createdAt.toString(),
    )
}

private fun DerivedMemoryEventDto.toDomain(): DerivedMemoryEvent {
    return DerivedMemoryEvent(
        id = id,
        kind = enumValueOf<DerivedMemoryEventKind>(kind),
        sourceReferenceIds = sourceReferenceIds,
        summary = summary,
        startedAt = startedAt?.let(Instant::parse),
        endedAt = endedAt?.let(Instant::parse),
        keywords = keywords,
        labels = labels,
        entities = entities,
        confidence = enumValueOf<ConfidenceLevel>(confidence),
        sensitivity = enumValueOf<SensitivityLevel>(sensitivity),
        citationIds = citationIds,
        createdAt = Instant.parse(createdAt),
    )
}

private fun MemoryCitation.toDto(): MemoryCitationDto {
    return MemoryCitationDto(
        id = id,
        sourceReferenceId = sourceReferenceId,
        derivedMemoryEventId = derivedMemoryEventId,
        label = label,
        observedAt = observedAt?.toString(),
        confidence = confidence.name,
    )
}

private fun MemoryCitationDto.toDomain(): MemoryCitation {
    return MemoryCitation(
        id = id,
        sourceReferenceId = sourceReferenceId,
        derivedMemoryEventId = derivedMemoryEventId,
        label = label,
        observedAt = observedAt?.let(Instant::parse),
        confidence = enumValueOf<ConfidenceLevel>(confidence),
    )
}

private fun DailyMemorySummary.toDto(): DailyMemorySummaryDto {
    return DailyMemorySummaryDto(
        id = id,
        date = date.toString(),
        summary = summary,
        derivedMemoryEventIds = derivedMemoryEventIds,
        placeClusterIds = placeClusterIds,
        appUsageSummaryIds = appUsageSummaryIds,
        confidence = confidence.name,
        missingSources = missingSources.map(MissingSource::toDto),
    )
}

private fun DailyMemorySummaryDto.toDomain(): DailyMemorySummary {
    return DailyMemorySummary(
        id = id,
        date = LocalDate.parse(date),
        summary = summary,
        derivedMemoryEventIds = derivedMemoryEventIds,
        placeClusterIds = placeClusterIds,
        appUsageSummaryIds = appUsageSummaryIds,
        confidence = enumValueOf<ConfidenceLevel>(confidence),
        missingSources = missingSources.map(MissingSourceDto::toDomain),
    )
}

private fun PlaceCluster.toDto(): PlaceClusterDto {
    return PlaceClusterDto(
        id = id,
        label = label,
        regionLabel = regionLabel,
        centroidLatitude = centroidLatitude,
        centroidLongitude = centroidLongitude,
        radiusMeters = radiusMeters,
        firstSeenAt = firstSeenAt?.toString(),
        lastSeenAt = lastSeenAt?.toString(),
        visitCount = visitCount,
        sourceReferenceIds = sourceReferenceIds,
        confidence = confidence.name,
    )
}

private fun PlaceClusterDto.toDomain(): PlaceCluster {
    return PlaceCluster(
        id = id,
        label = label,
        regionLabel = regionLabel,
        centroidLatitude = centroidLatitude,
        centroidLongitude = centroidLongitude,
        radiusMeters = radiusMeters,
        firstSeenAt = firstSeenAt?.let(Instant::parse),
        lastSeenAt = lastSeenAt?.let(Instant::parse),
        visitCount = visitCount,
        sourceReferenceIds = sourceReferenceIds,
        confidence = enumValueOf<ConfidenceLevel>(confidence),
    )
}

private fun AppUsageSummary.toDto(): AppUsageSummaryDto {
    return AppUsageSummaryDto(
        id = id,
        sourceReferenceIds = sourceReferenceIds,
        date = date.toString(),
        packageName = packageName,
        appAlias = appAlias,
        category = category.name,
        totalDurationMinutes = totalDurationMinutes,
        launchCount = launchCount,
        activeTimeBucketLabels = activeTimeBucketLabels,
        confidence = confidence.name,
    )
}

private fun AppUsageSummaryDto.toDomain(): AppUsageSummary {
    return AppUsageSummary(
        id = id,
        sourceReferenceIds = sourceReferenceIds,
        date = LocalDate.parse(date),
        packageName = packageName,
        appAlias = appAlias,
        category = enumValueOf<AppUsageCategory>(category),
        totalDurationMinutes = totalDurationMinutes,
        launchCount = launchCount,
        activeTimeBucketLabels = activeTimeBucketLabels,
        confidence = enumValueOf<ConfidenceLevel>(confidence),
    )
}

private fun ConnectorScanStatus.toDto(): ConnectorScanStatusDto {
    return ConnectorScanStatusDto(
        connectorId = connectorId,
        processingState = processingState.name,
        missingSources = missingSources.map(MissingSource::toDto),
        scopeFrom = scopeFrom?.toString(),
        scopeUntil = scopeUntil?.toString(),
        scannedAt = scannedAt.toString(),
    )
}

private fun ConnectorScanStatusDto.toDomain(): ConnectorScanStatus {
    return ConnectorScanStatus(
        connectorId = connectorId,
        processingState = enumValueOf<ProcessingState>(processingState),
        missingSources = missingSources.map(MissingSourceDto::toDomain),
        scopeFrom = scopeFrom?.let(Instant::parse),
        scopeUntil = scopeUntil?.let(Instant::parse),
        scannedAt = Instant.parse(scannedAt),
    )
}

private fun MissingSource.toDto(): MissingSourceDto {
    return MissingSourceDto(
        capability = capability.name,
        availability = availability.name,
        explanation = explanation,
        connectorId = connectorId,
        issueCode = issueCode?.storageKey,
    )
}

private fun MissingSourceDto.toDomain(): MissingSource {
    return MissingSource(
        capability = enumValueOf<MemoryCapability>(capability),
        availability = enumValueOf<SourceAvailability>(availability),
        explanation = explanation,
        connectorId = connectorId,
        issueCode = issueCode?.let { storageKey ->
            requireNotNull(ConnectorScanIssueCode.fromStorageKey(storageKey))
        },
    )
}

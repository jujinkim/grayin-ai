package ai.grayin.app

import android.content.Context
import android.net.Uri
import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.core.grounding.GroundedAnswerRequest
import ai.grayin.core.grounding.TemplateGroundedAnswerGenerator
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.retrieval.DefaultQueryPlanner
import ai.grayin.core.retrieval.QueryPlan
import ai.grayin.core.store.LocalMemoryStore
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConnectorUiState(
    val id: String,
    val name: String,
    val status: String,
    val sensitivity: String,
    val description: String,
    val canAdd: Boolean = false,
    val canIndex: Boolean = false,
    val canRevoke: Boolean = false,
    val canDelete: Boolean = false,
)

data class AnswerUiState(
    val answer: String,
    val confidence: String,
    val evidenceRows: List<String>,
    val missingRows: List<String>,
)

data class GrayinSnapshot(
    val sourceRows: List<ConnectorUiState>,
    val timelineRows: List<String>,
    val placesRows: List<String>,
    val settingsRows: List<String>,
)

class GrayinMemoryController(
    context: Context,
    private val store: LocalMemoryStore = SqlCipherLocalMemoryStore(context.applicationContext),
    val localFilesConnector: LocalFilesConnector = LocalFilesConnector(context.applicationContext),
    private val queryPlanner: DefaultQueryPlanner = DefaultQueryPlanner(),
    private val answerGenerator: TemplateGroundedAnswerGenerator = TemplateGroundedAnswerGenerator(),
) {
    suspend fun snapshot(strings: GrayinStrings): GrayinSnapshot = withContext(Dispatchers.IO) {
        val sourceReferences = store.loadSourceReferences()
        val events = store.loadDerivedMemoryEvents()
        val localState = localFilesConnector.currentState()
        GrayinSnapshot(
            sourceRows = staticConnectorRows(strings) + ConnectorUiState(
                id = LocalFileMemoryExtractor.CONNECTOR_ID,
                name = strings.localFiles,
                status = when {
                    localState.processingState == ProcessingState.COMPLETED -> strings.indexed
                    localState.enabled -> strings.selected
                    else -> strings.off
                },
                sensitivity = strings.highSensitivity,
                description = when {
                    localState.processingState == ProcessingState.COMPLETED -> strings.localFilesIndexedDescription
                    localState.enabled -> strings.localFilesSelectedDescription
                    else -> strings.localFilesOffDescription
                },
                canAdd = true,
                canIndex = localState.enabled,
                canRevoke = localState.enabled,
                canDelete = sourceReferences.any { it.connectorId == LocalFileMemoryExtractor.CONNECTOR_ID },
            ),
            timelineRows = timelineRows(events, strings),
            placesRows = listOf(strings.noPlaceClusters),
            settingsRows = listOf(
                strings.networkPermissionRestricted,
                strings.accountAbsent,
                strings.cloudSyncAbsent,
                strings.telemetryAbsent,
                strings.crashAnalyticsAbsent,
                strings.encryptedStoreSqlCipher,
                "${strings.indexedSourceReferencesPrefix} ${sourceReferences.size}",
                "${strings.derivedMemoryEventsPrefix} ${events.size}",
            ),
        )
    }

    suspend fun indexLocalFiles(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val scanResult = localFilesConnector.scan(ai.grayin.core.connector.ConnectorScanScope(forceRefresh = true))
        store.saveSourceReferences(scanResult.sourceReferences)
        store.saveDerivedMemoryEvents(scanResult.derivedEvents)
        store.saveCitations(scanResult.citations)
        when {
            scanResult.derivedEvents.isNotEmpty() -> {
                strings.indexedLocalFileEvents(scanResult.derivedEvents.size)
            }

            scanResult.missingSources.isNotEmpty() -> {
                scanResult.missingSources.first().explanation
            }

            else -> strings.noLocalFilesIndexed
        }
    }

    suspend fun rememberSelectedLocalFile(uri: Uri, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        if (localFilesConnector.rememberSelectedFile(uri)) {
            strings.selectedLocalFile
        } else {
            strings.unsupportedFileOrPermissionDenied
        }
    }

    suspend fun deleteLocalFileData(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val deleteResult = store.deleteConnectorData(LocalFileMemoryExtractor.CONNECTOR_ID)
        strings.deletedLocalFileEvents(deleteResult.deletedDerivedMemoryEventIds.size)
    }

    suspend fun revokeLocalFiles(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val revokeResult = localFilesConnector.revoke()
        store.deleteConnectorData(revokeResult.connectorId)
        strings.revokedLocalFiles
    }

    suspend fun ask(query: String, strings: GrayinStrings): AnswerUiState = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyAnswer(strings.enterMemoryQuestion, strings)

        val availableCapabilities = availableCapabilities()
        val plan = queryPlanner.plan(trimmedQuery, availableCapabilities)
        val evidenceItems = evidenceFor(trimmedQuery, plan)
        val usedCitationIds = evidenceItems.flatMap { it.citationIds }.toSet()
        val citations = store.loadCitations().filter { it.id in usedCitationIds }
        val missingSources = missingSourcesFor(plan, evidenceItems, strings)
        val evidencePack = EvidencePack(
            id = "evidence:${Instant.now().toEpochMilli()}",
            query = trimmedQuery,
            generatedAt = Instant.now(),
            evidenceItems = evidenceItems,
            citations = citations,
            missingSources = missingSources,
        )
        val answer = answerGenerator.generate(GroundedAnswerRequest(evidencePack))

        AnswerUiState(
            answer = if (answer.evidence.isEmpty()) strings.cannotAnswerFromIndexedEvidence else answer.answer,
            confidence = answer.confidence.name,
            evidenceRows = if (answer.evidence.isEmpty()) {
                listOf(strings.noCitedEvidence)
            } else {
                answer.evidence.map { item ->
                    val labels = citations
                        .filter { it.id in item.citationIds }
                        .joinToString { it.label }
                    "${item.summary} ($labels)"
                }
            },
            missingRows = if (answer.missingData.isEmpty()) {
                listOf(strings.noMissingSources)
            } else {
                answer.missingData.map { missingSourceRow(it, strings) }
            },
        )
    }

    private suspend fun availableCapabilities(): Set<MemoryCapability> {
        val localEvents = store.loadDerivedMemoryEvents().filter { event ->
            event.sourceReferenceIds.any { it.startsWith("source:${LocalFileMemoryExtractor.CONNECTOR_ID}:") }
        }
        return if (localEvents.isEmpty()) {
            emptySet()
        } else {
            setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT)
        }
    }

    private suspend fun evidenceFor(query: String, plan: QueryPlan): List<EvidenceItem> {
        val queryTokens = tokenize(query)
        val events = store.loadDerivedMemoryEvents().filter { event ->
            val timeRange = plan.timeRange
            val occurredAt = event.startedAt ?: event.createdAt
            timeRange == null ||
                (occurredAt >= timeRange.startInclusive && occurredAt < timeRange.endExclusive)
        }
        val scored = events.map { event -> event to score(event, queryTokens) }
        val selected = if (scored.any { it.second > 0 }) {
            scored.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(MAX_EVIDENCE_ITEMS)
        } else {
            events.sortedByDescending { it.createdAt }.take(MAX_EVIDENCE_ITEMS)
        }

        return selected.map { event ->
            EvidenceItem(
                id = "evidence:${event.id}",
                derivedMemoryEventId = event.id,
                summary = event.summary,
                eventKind = event.kind,
                occurredAt = event.startedAt ?: event.createdAt,
                confidence = event.confidence,
                citationIds = event.citationIds,
                capabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT),
            )
        }
    }

    private fun score(event: DerivedMemoryEvent, queryTokens: Set<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val keywordScore = event.keywords.count { it in queryTokens } * 3
        val labelScore = event.labels.count { it.lowercase() in queryTokens }
        val summary = event.summary.lowercase()
        val summaryScore = queryTokens.count { summary.contains(it) }
        return keywordScore + labelScore + summaryScore
    }

    private fun missingSourcesFor(
        plan: QueryPlan,
        evidenceItems: List<EvidenceItem>,
        strings: GrayinStrings,
    ): List<MissingSource> {
        if (evidenceItems.isNotEmpty()) return plan.missingSources
        return plan.missingSources + MissingSource(
            capability = MemoryCapability.HAS_TEXT,
            availability = SourceAvailability.NOT_INDEXED,
            explanation = strings.noLocalTextEvidenceIndexed,
            connectorId = LocalFileMemoryExtractor.CONNECTOR_ID,
        )
    }

    private fun staticConnectorRows(strings: GrayinStrings): List<ConnectorUiState> {
        return listOf(
            ConnectorUiState(
                id = "location",
                name = strings.location,
                status = strings.notImplemented,
                sensitivity = strings.highSensitivity,
                description = strings.connectorInvocationUnavailable,
            ),
            ConnectorUiState(
                id = "photos",
                name = strings.photos,
                status = strings.notImplemented,
                sensitivity = strings.highSensitivity,
                description = strings.connectorInvocationUnavailable,
            ),
            ConnectorUiState(
                id = "calendar",
                name = strings.calendar,
                status = strings.notImplemented,
                sensitivity = strings.highSensitivity,
                description = strings.connectorInvocationUnavailable,
            ),
            ConnectorUiState(
                id = "notifications",
                name = strings.notifications,
                status = strings.notImplemented,
                sensitivity = strings.veryHighSensitivity,
                description = strings.connectorInvocationUnavailable,
            ),
            ConnectorUiState(
                id = "app_usage",
                name = strings.appUsage,
                status = strings.notImplemented,
                sensitivity = strings.veryHighSensitivity,
                description = strings.connectorInvocationUnavailable,
            ),
        )
    }

    private fun timelineRows(events: List<DerivedMemoryEvent>, strings: GrayinStrings): List<String> {
        if (events.isEmpty()) return listOf(strings.noDerivedEvents)
        return events.sortedByDescending { it.createdAt }
            .take(MAX_TIMELINE_ROWS)
            .map { "${it.createdAt}: ${it.summary}" }
    }

    private fun emptyAnswer(message: String, strings: GrayinStrings): AnswerUiState {
        return AnswerUiState(
            answer = message,
            confidence = ConfidenceLevel.UNKNOWN.name,
            evidenceRows = listOf(strings.noCitedEvidence),
            missingRows = listOf(strings.askFromIndexedEvidence),
        )
    }

    private fun missingSourceRow(missingSource: MissingSource, strings: GrayinStrings): String {
        val explanation = if (
            missingSource.explanation.startsWith("Required capability ") ||
            missingSource.explanation.startsWith("Optional capability ")
        ) {
            strings.capabilityUnavailable(missingSource.capability.name)
        } else {
            missingSource.explanation
        }
        return "${missingSource.capability.name}: $explanation"
    }

    private fun tokenize(value: String): Set<String> {
        return Regex("[\\p{L}\\p{Nd}]+").findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= MIN_QUERY_TOKEN_LENGTH }
            .toSet()
    }

    private companion object {
        const val MAX_EVIDENCE_ITEMS = 8
        const val MAX_TIMELINE_ROWS = 30
        const val MIN_QUERY_TOKEN_LENGTH = 3
    }
}

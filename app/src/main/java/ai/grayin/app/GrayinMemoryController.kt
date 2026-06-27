package ai.grayin.app

import android.content.Context
import android.net.Uri
import ai.grayin.connectors.calendar.CalendarConnector
import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.connectors.location.LocationConnector
import ai.grayin.connectors.notification.NotificationConnector
import ai.grayin.connectors.photos.PhotosConnector
import ai.grayin.connectors.usagestats.AppUsageConnector
import ai.grayin.core.ai.Gemma4LocalLanguageModel
import ai.grayin.core.ai.LocalLanguageModel
import ai.grayin.core.ai.LocalModelAnswerDraft
import ai.grayin.core.ai.LocalModelStatus
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.grounding.GroundedAnswerRequest
import ai.grayin.core.grounding.GroundedAnswerGenerator
import ai.grayin.core.grounding.TemplateGroundedAnswerGenerator
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceReference
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
    val canInvoke: Boolean = false,
    val requiredPermissions: List<String> = emptyList(),
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
    private val sourceConnectors: List<MemoryConnector> = listOf(
        LocationConnector(context.applicationContext),
        PhotosConnector(context.applicationContext),
        CalendarConnector(context.applicationContext),
        NotificationConnector(context.applicationContext),
        AppUsageConnector(context.applicationContext),
        localFilesConnector,
    ),
    private val queryPlanner: DefaultQueryPlanner = DefaultQueryPlanner(),
    private val localLanguageModel: LocalLanguageModel = Gemma4LocalLanguageModel(context.applicationContext),
    private val fallbackAnswerGenerator: GroundedAnswerGenerator = TemplateGroundedAnswerGenerator(),
) {
    suspend fun snapshot(strings: GrayinStrings): GrayinSnapshot = withContext(Dispatchers.IO) {
        val sourceReferences = store.loadSourceReferences()
        val events = store.loadDerivedMemoryEvents()
        val localModelStatus = runCatching { localLanguageModel.status() }
            .getOrDefault(LocalModelStatus.UNAVAILABLE)
        GrayinSnapshot(
            sourceRows = sourceConnectors.map { connector -> sourceRow(connector, sourceReferences, strings) },
            timelineRows = timelineRows(events, strings),
            placesRows = listOf(strings.noPlaceClusters),
            settingsRows = listOf(
                strings.networkPermissionRestricted,
                strings.accountAbsent,
                strings.cloudSyncAbsent,
                strings.telemetryAbsent,
                strings.crashAnalyticsAbsent,
                strings.encryptedStoreSqlCipher,
            ) + localModelRows(localModelStatus, strings) + listOf(
                "${strings.indexedSourceReferencesPrefix} ${sourceReferences.size}",
                "${strings.derivedMemoryEventsPrefix} ${events.size}",
            ),
        )
    }

    private fun localModelRows(status: LocalModelStatus, strings: GrayinStrings): List<String> {
        val statusRow = when (status) {
            LocalModelStatus.READY -> strings.localGemmaModelReady
            LocalModelStatus.LOADING -> strings.localGemmaModelLoading
            LocalModelStatus.UNAVAILABLE -> strings.localGemmaModelUnavailable
        }
        return listOf(
            strings.localGemmaModelTitle,
            statusRow,
            strings.localGemmaModelApkNotBundled,
            strings.localGemmaModelDownloadGuide,
            strings.localGemmaModelDownloadUrl,
            strings.localGemmaModelFileGuide,
            strings.localGemmaModelDevInstallGuide,
        )
    }

    suspend fun indexLocalFiles(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val scanResult = localFilesConnector.scan(ConnectorScanScope(forceRefresh = true))
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

    suspend fun indexConnector(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        if (connectorId == LocalFileMemoryExtractor.CONNECTOR_ID) return@withContext indexLocalFiles(strings)
        val connector = connectorFor(connectorId)
        val scanResult = scanAndStore(connector)
        when {
            scanResult.derivedEvents.isNotEmpty() -> {
                strings.indexedConnectorEvents(
                    connectorName = strings.connectorName(connectorId, connector.metadata.displayName),
                    count = scanResult.derivedEvents.size,
                )
            }

            scanResult.missingSources.isNotEmpty() -> scanResult.missingSources.first().explanation
            else -> strings.noLocalFilesIndexed
        }
    }

    suspend fun indexAllEnabledSources(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        var scannedSources = 0
        var indexedEvents = 0
        var skippedSources = 0

        sourceConnectors.forEach { connector ->
            val state = connector.currentState()
            if (state.enabled) {
                scannedSources += 1
                val scanResult = scanAndStore(connector)
                indexedEvents += scanResult.derivedEvents.size
                if (scanResult.derivedEvents.isEmpty() && scanResult.missingSources.isNotEmpty()) {
                    skippedSources += 1
                }
            }
        }

        when {
            scannedSources == 0 -> strings.noSourcesReadyToIndex
            else -> strings.indexedAllSources(indexedEvents, scannedSources, skippedSources)
        }
    }

    suspend fun invokeConnector(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        if (connector !is InvokableMemoryConnector) return@withContext strings.connectorConnectionUnavailable
        val permissionState = connector.invoke()
        if (!permissionState.permissionGranted) return@withContext strings.sourcePermissionDenied
        strings.connectedConnector(strings.connectorName(connectorId, connector.metadata.displayName))
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

    suspend fun deleteConnectorData(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val deleteResult = store.deleteConnectorData(connectorId)
        if (connectorId == LocalFileMemoryExtractor.CONNECTOR_ID) {
            strings.deletedLocalFileEvents(deleteResult.deletedDerivedMemoryEventIds.size)
        } else {
            strings.deletedConnectorEvents(
                connectorName = strings.connectorName(connectorId, connectorId),
                count = deleteResult.deletedDerivedMemoryEventIds.size,
            )
        }
    }

    suspend fun revokeLocalFiles(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val revokeResult = localFilesConnector.revoke()
        store.deleteConnectorData(revokeResult.connectorId)
        strings.revokedLocalFiles
    }

    suspend fun revokeConnector(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        val revokeResult = connector.revoke()
        store.deleteConnectorData(revokeResult.connectorId)
        if (connectorId == LocalFileMemoryExtractor.CONNECTOR_ID) {
            strings.revokedLocalFiles
        } else {
            strings.revokedConnector(strings.connectorName(connectorId, connector.metadata.displayName))
        }
    }

    private fun connectorFor(connectorId: String): MemoryConnector {
        return sourceConnectors.first { it.metadata.connectorId == connectorId }
    }

    private suspend fun scanAndStore(connector: MemoryConnector): ConnectorScanResult {
        val scanResult = connector.scan(ConnectorScanScope(forceRefresh = true))
        store.saveSourceReferences(scanResult.sourceReferences)
        store.saveDerivedMemoryEvents(scanResult.derivedEvents)
        store.saveCitations(scanResult.citations)
        return scanResult
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
        val localDraft = localAnswerDraft(evidencePack)
        if (localDraft != null) {
            return@withContext localDraft.toAnswerUiState(evidenceItems, citations, strings)
        }

        val answer = fallbackAnswerGenerator.generate(GroundedAnswerRequest(evidencePack))

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

    private suspend fun localAnswerDraft(evidencePack: EvidencePack): LocalModelAnswerDraft? {
        if (evidencePack.evidenceItems.isEmpty() || evidencePack.citations.isEmpty()) return null
        if (localLanguageModel.status() != LocalModelStatus.READY) return null
        return runCatching { localLanguageModel.generate(evidencePack) }.getOrNull()
            ?.takeIf { draft -> draft.usedEvidenceItemIds.isNotEmpty() && draft.answer.isNotBlank() }
    }

    private fun LocalModelAnswerDraft.toAnswerUiState(
        evidenceItems: List<EvidenceItem>,
        citations: List<MemoryCitation>,
        strings: GrayinStrings,
    ): AnswerUiState {
        val usedEvidenceIds = usedEvidenceItemIds.toSet()
        val usedEvidence = evidenceItems.filter { it.id in usedEvidenceIds }.ifEmpty { evidenceItems }
        return AnswerUiState(
            answer = answer,
            confidence = confidence.name,
            evidenceRows = usedEvidence.map { item ->
                val labels = citations
                    .filter { it.id in item.citationIds }
                    .joinToString { it.label }
                "${item.summary} ($labels)"
            },
            missingRows = if (missingSources.isEmpty()) {
                listOf(strings.noMissingSources)
            } else {
                missingSources.map { missingSourceRow(it, strings) }
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

    private suspend fun sourceRow(
        connector: MemoryConnector,
        sourceReferences: List<SourceReference>,
        strings: GrayinStrings,
    ): ConnectorUiState {
        val state = connector.currentState()
        val permissionState = connector.permissionState()
        val connectorId = connector.metadata.connectorId
        val isLocalFiles = connectorId == LocalFileMemoryExtractor.CONNECTOR_ID
        val connectorName = strings.connectorName(connectorId, connector.metadata.displayName)
        return ConnectorUiState(
            id = connectorId,
            name = connectorName,
            status = sourceStatus(state, permissionState, strings),
            sensitivity = sensitivityLabel(connector.metadata.sensitivity, strings),
            canInvoke = !isLocalFiles && permissionState.canRequestPermission && !state.enabled,
            requiredPermissions = permissionState.requiredPlatformPermissions,
            canAdd = isLocalFiles,
            canIndex = state.enabled,
            canRevoke = state.enabled,
            canDelete = sourceReferences.any { it.connectorId == connectorId },
        )
    }

    private fun sourceStatus(
        state: ConnectorState,
        permissionState: ConnectorPermissionState,
        strings: GrayinStrings,
    ): String {
        return when {
            state.processingState == ProcessingState.COMPLETED -> strings.indexed
            state.enabled -> strings.selected
            !permissionState.canRequestPermission && !permissionState.permissionGranted -> strings.notImplemented
            else -> strings.off
        }
    }

    private fun sensitivityLabel(level: SensitivityLevel, strings: GrayinStrings): String {
        return when (level) {
            SensitivityLevel.VERY_HIGH -> strings.veryHighSensitivity
            SensitivityLevel.HIGH -> strings.highSensitivity
            SensitivityLevel.MEDIUM -> "Medium sensitivity"
            SensitivityLevel.LOW -> "Low sensitivity"
        }
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

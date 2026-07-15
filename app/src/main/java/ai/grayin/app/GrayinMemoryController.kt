package ai.grayin.app

import android.content.Context
import android.net.Uri
import ai.grayin.connectors.calendar.CalendarConnector
import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.connectors.location.LocationConnector
import ai.grayin.connectors.notification.NotificationAppAllowlist
import ai.grayin.connectors.notification.NotificationConnector
import ai.grayin.connectors.photos.PhotosConnector
import ai.grayin.connectors.usagestats.AppUsageConnector
import ai.grayin.core.ai.Gemma4LocalLanguageModel
import ai.grayin.core.ai.Gemma4ModelPathResolver
import ai.grayin.core.ai.LocalLanguageModel
import ai.grayin.core.ai.LocalModelGrounding
import ai.grayin.core.ai.LocalModelAnswerDraft
import ai.grayin.core.ai.LocalModelStatus
import ai.grayin.core.ai.ModelCatalog
import ai.grayin.core.ai.ModelCatalogEntry
import ai.grayin.core.ai.ModelDownloadScheduler
import ai.grayin.core.ai.ModelDownloadStatus
import ai.grayin.core.ai.ModelInstallRecord
import ai.grayin.core.ai.ModelInstallStore
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
import ai.grayin.core.model.SourceReference
import ai.grayin.core.retrieval.DefaultQueryPlanner
import ai.grayin.core.retrieval.MissingEvidenceResolver
import ai.grayin.core.retrieval.MemoryCapabilityResolver
import ai.grayin.core.retrieval.QueryPlan
import ai.grayin.core.store.LocalMemoryStore
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.time.Instant
import java.util.Locale
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
    val notificationAllowedPackages: List<String> = emptyList(),
)

data class AnswerUiState(
    val answer: String,
    val confidence: String,
    val evidenceRows: List<String>,
    val missingRows: List<String>,
)

data class ModelOptionUiState(
    val id: String,
    val name: String,
    val status: String,
    val detailRows: List<String>,
    val selected: Boolean,
    val recommended: Boolean,
    val downloadPageUrl: String?,
    val canSelect: Boolean,
    val canDownload: Boolean,
    val canCancelDownload: Boolean,
    val canDeleteDownloaded: Boolean,
)

data class GrayinSnapshot(
    val sourceRows: List<ConnectorUiState>,
    val timelineRows: List<String>,
    val placesRows: List<String>,
    val settingsRows: List<String>,
    val modelOptions: List<ModelOptionUiState>,
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
    private val notificationAllowlist: NotificationAppAllowlist = NotificationAppAllowlist(context.applicationContext),
    private val modelInstallStore: ModelInstallStore = ModelInstallStore(context.applicationContext),
    private val modelDownloadScheduler: ModelDownloadScheduler = ModelDownloadScheduler(context.applicationContext),
    private val modelPathResolver: Gemma4ModelPathResolver = Gemma4ModelPathResolver(
        context.applicationContext,
        modelInstallStore,
    ),
    private val localLanguageModel: LocalLanguageModel = Gemma4LocalLanguageModel(
        context.applicationContext,
        modelPathResolver,
    ),
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
            modelOptions = localModelOptions(strings),
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
            strings.localGemmaModelDevInstallGuide,
        )
    }

    private fun localModelOptions(strings: GrayinStrings): List<ModelOptionUiState> {
        val selectedModelId = modelInstallStore.selectedModelId()
        return ModelCatalog.entries.map { entry ->
            val record = modelInstallStore.recordFor(entry)
            val selected = entry.id == selectedModelId
            ModelOptionUiState(
                id = entry.id,
                name = entry.displayName,
                status = strings.localModelStatus(record.status),
                detailRows = localModelDetailRows(entry, record, strings),
                selected = selected,
                recommended = entry.recommended,
                downloadPageUrl = entry.downloadPageUrl,
                canSelect = !selected,
                canDownload = entry.downloadUrl != null &&
                    record.status != ModelDownloadStatus.READY &&
                    record.status != ModelDownloadStatus.QUEUED &&
                    record.status != ModelDownloadStatus.DOWNLOADING,
                canCancelDownload = record.status == ModelDownloadStatus.QUEUED ||
                    record.status == ModelDownloadStatus.DOWNLOADING,
                canDeleteDownloaded = record.status == ModelDownloadStatus.READY,
            )
        }
    }

    private fun localModelDetailRows(
        entry: ModelCatalogEntry,
        record: ModelInstallRecord,
        strings: GrayinStrings,
    ): List<String> {
        return buildList {
            add(strings.localModelProvider(entry.providerLabel))
            add(strings.localModelLicense(entry.licenseLabel))
            add(strings.localModelApproxSize(formatGigabytes(entry.approxSizeBytes)))
            add(strings.localModelRecommendedRam(entry.recommendedRamGb))
            add(strings.localModelFileName(entry.fileName))
            if (entry.recommended) add(strings.localModelRecommended)
            if (entry.downloadUrl == null) add(strings.localModelDownloadUnavailable)
            if (record.status == ModelDownloadStatus.QUEUED || record.status == ModelDownloadStatus.DOWNLOADING) {
                add(strings.localModelRequiresUnmeteredNetwork)
                record.progressPercent?.let { progress -> add(strings.localModelProgress(progress)) }
            }
            if (record.status == ModelDownloadStatus.FAILED && record.failureReason != null) {
                add(strings.localModelFailure(record.failureReason))
            }
            if (record.status == ModelDownloadStatus.READY && record.installedBytes > 0L) {
                add(strings.localModelInstalledSize(formatGigabytes(record.installedBytes)))
            }
        }
    }

    private fun formatGigabytes(bytes: Long): String {
        return String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
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

    suspend fun importLocalGemmaModel(uri: Uri, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        runCatching { modelPathResolver.installModelFromUri(uri) }
            .fold(
                onSuccess = { strings.localGemmaModelImported },
                onFailure = { error ->
                    if (error is IllegalArgumentException) {
                        strings.localGemmaModelInvalidFile
                    } else {
                        strings.localGemmaModelImportFailed
                    }
                },
            )
    }

    suspend fun selectLocalModel(modelId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val entry = ModelCatalog.entry(modelId) ?: return@withContext strings.localModelUnknown
        modelInstallStore.selectModel(entry.id)
        strings.localModelSelected(entry.displayName)
    }

    suspend fun downloadLocalModel(modelId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val entry = ModelCatalog.entry(modelId) ?: return@withContext strings.localModelUnknown
        if (entry.downloadUrl == null) return@withContext strings.localModelDownloadUnavailable
        modelInstallStore.selectModel(entry.id)
        modelInstallStore.recordQueued(entry.id)
        modelDownloadScheduler.enqueue(entry.id)
        strings.localModelDownloadQueued(entry.displayName)
    }

    suspend fun cancelLocalModelDownload(modelId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val entry = ModelCatalog.entry(modelId) ?: return@withContext strings.localModelUnknown
        modelDownloadScheduler.cancel(entry.id)
        modelInstallStore.recordNotDownloaded(entry.id)
        strings.localModelDownloadCanceled(entry.displayName)
    }

    suspend fun deleteDownloadedLocalModel(modelId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val entry = ModelCatalog.entry(modelId) ?: return@withContext strings.localModelUnknown
        modelDownloadScheduler.cancel(entry.id)
        val deleted = modelInstallStore.deleteInstalledModel(entry.id)
        if (deleted) {
            strings.localModelDeleted(entry.displayName)
        } else {
            strings.localModelDeleteMissing(entry.displayName)
        }
    }

    suspend fun deleteLocalGemmaModel(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        if (modelPathResolver.deleteImportedModel()) {
            strings.localGemmaModelDeleted
        } else {
            strings.localGemmaModelDeleteMissing
        }
    }

    suspend fun updateNotificationAllowlist(rawValue: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val parsed = NotificationAppAllowlist.parse(rawValue)
        if (parsed.invalidEntries.isNotEmpty()) return@withContext strings.notificationAllowlistInvalid
        notificationAllowlist.replace(parsed.allowedPackages)
        strings.notificationAllowlistSaved(parsed.allowedPackages.size)
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
        val missingSources = MissingEvidenceResolver.resolve(
            plan = plan,
            hasEvidence = evidenceItems.isNotEmpty(),
            noMatchingEvidenceExplanation = strings.noAnswerAvailable,
        )
        val evidencePack = LocalModelGrounding.citedEvidencePack(
            EvidencePack(
                id = "evidence:${Instant.now().toEpochMilli()}",
                query = trimmedQuery,
                generatedAt = Instant.now(),
                evidenceItems = evidenceItems,
                citations = citations,
                missingSources = missingSources,
            ),
        )
        val localDraft = localAnswerDraft(evidencePack)
        if (localDraft != null) {
            return@withContext localDraft.toAnswerUiState(evidencePack.evidenceItems, evidencePack.citations, strings)
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
            ?.let { draft -> LocalModelGrounding.validateDraft(evidencePack, draft) }
    }

    private fun LocalModelAnswerDraft.toAnswerUiState(
        evidenceItems: List<EvidenceItem>,
        citations: List<MemoryCitation>,
        strings: GrayinStrings,
    ): AnswerUiState {
        val usedEvidenceIds = usedEvidenceItemIds.toSet()
        val usedEvidence = evidenceItems.filter { it.id in usedEvidenceIds }
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
        return MemoryCapabilityResolver.forEvents(store.loadDerivedMemoryEvents())
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
                capabilities = MemoryCapabilityResolver.forEvent(event),
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
            notificationAllowedPackages = if (connectorId == NotificationConnector.CONNECTOR_ID) {
                notificationAllowlist.load().toList()
            } else {
                emptyList()
            },
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

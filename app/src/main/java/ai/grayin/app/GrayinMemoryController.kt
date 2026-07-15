package ai.grayin.app

import android.content.Context
import android.net.Uri
import android.os.Build
import ai.grayin.connectors.AndroidConnectorRegistry
import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFilesConnector
import ai.grayin.connectors.location.LocationConnector
import ai.grayin.connectors.notification.NotificationAppAllowlist
import ai.grayin.connectors.notification.NotificationConnector
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
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.connector.MemoryConnectorRegistry
import ai.grayin.core.grounding.GroundedAnswerRequest
import ai.grayin.core.grounding.GroundedAnswerGenerator
import ai.grayin.core.grounding.TemplateGroundedAnswerGenerator
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceReference
import ai.grayin.core.enrichment.OnlineEnrichmentPreferences
import ai.grayin.core.indexing.ConnectorScanWriter
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStore
import ai.grayin.core.indexing.IndexConnector
import ai.grayin.core.indexing.IndexNow
import ai.grayin.core.indexing.IndexingCommand
import ai.grayin.core.indexing.IndexingCommandExecutor
import ai.grayin.core.indexing.IndexingQueue
import ai.grayin.core.indexing.IndexingQueueItem
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTrigger
import ai.grayin.core.indexing.ManualIndexDateRange
import ai.grayin.core.retrieval.ConnectorScanMissingResolver
import ai.grayin.core.retrieval.DefaultQueryPlanner
import ai.grayin.core.retrieval.EvidenceEventSelector
import ai.grayin.core.retrieval.MissingEvidenceResolver
import ai.grayin.core.retrieval.MemoryCapabilityResolver
import ai.grayin.core.retrieval.QueryPlan
import ai.grayin.core.retrieval.ScopedQueryPlanning
import ai.grayin.core.ocr.OcrLanguagePackCatalog
import ai.grayin.core.ocr.OcrLanguagePackDownloadScheduler
import ai.grayin.core.ocr.OcrLanguagePackInstallStore
import ai.grayin.core.ocr.OcrLanguagePackStatus
import ai.grayin.core.store.LocalMemoryStore
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import ai.grayin.core.transfer.BackupFileFailureCode
import ai.grayin.core.transfer.BackupFileResult
import ai.grayin.core.transfer.EncryptedBackupStagingStore
import ai.grayin.core.transfer.GrayinTransferEnvelopeCodec
import ai.grayin.core.transfer.StagedBackupCiphertext
import ai.grayin.core.transfer.TransferFailureCode
import ai.grayin.core.transfer.TransferFailure
import ai.grayin.core.transfer.TransferPayload
import ai.grayin.core.transfer.TransferProducerMetadata
import ai.grayin.core.transfer.TransferResult
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    val onlineEnrichmentEnabled: Boolean? = null,
    val detailRows: List<String> = emptyList(),
    val supportsDateRangeIndexing: Boolean = false,
    val lastRequestedScanRange: IndexedDateRangePresentation? = null,
)

sealed interface ConnectorConnectionResult {
    data class Connected(val connectorId: String, val connectorName: String) : ConnectorConnectionResult

    data class PermissionRequired(val connectorId: String) : ConnectorConnectionResult

    data class Unavailable(val connectorId: String) : ConnectorConnectionResult
}

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

data class OcrLanguagePackUiState(
    val id: String,
    val name: String,
    val status: String,
    val detailRows: List<String>,
    val canDownload: Boolean,
    val canCancelDownload: Boolean,
    val canDelete: Boolean,
)

data class PreparedEncryptedExport(
    val token: String,
    val suggestedFileName: String,
    val sizeBytes: Long,
)

data class StagedEncryptedImport(
    val token: String,
    val sizeBytes: Long,
)

data class ImportedBackupSummary(
    val derivedMemoryEventCount: Int,
    val connectorCount: Int,
)

data class GrayinSnapshot(
    val sourceRows: List<ConnectorUiState>,
    val indexingStatus: IndexingStatusUiState,
    val timelineRows: List<String>,
    val placesRows: List<String>,
    val settingsRows: List<String>,
    val modelOptions: List<ModelOptionUiState>,
    val ocrLanguagePacks: List<OcrLanguagePackUiState>,
)

class GrayinMemoryController(
    context: Context,
    private val store: LocalMemoryStore = SqlCipherLocalMemoryStore(context.applicationContext),
    private val indexingQueue: IndexingQueue = store as? IndexingQueue
        ?: error("The local memory store must also provide the encrypted indexing queue."),
    private val automaticIndexingRuntimeStore: AutomaticIndexingRuntimeStore =
        store as? AutomaticIndexingRuntimeStore
            ?: error("The local memory store must provide automatic indexing runtime status."),
    private val connectorScanWriter: ConnectorScanWriter = store as? ConnectorScanWriter
        ?: error("The local memory store must provide fenced connector scan commits."),
    private val androidConnectors: AndroidConnectorRegistry = AndroidConnectorRegistry(context.applicationContext),
    val localFilesConnector: LocalFilesConnector = androidConnectors.localFilesConnector,
    private val connectorRegistry: MemoryConnectorRegistry = androidConnectors.registry,
    private val indexingExecutor: IndexingCommandExecutor = IndexingCommandExecutor(
        connectorRegistry = connectorRegistry,
        queue = indexingQueue,
        scanWriter = connectorScanWriter,
        reconsentGate = store,
    ),
    private val queryPlanner: DefaultQueryPlanner = DefaultQueryPlanner(),
    private val notificationAllowlist: NotificationAppAllowlist = NotificationAppAllowlist(context.applicationContext),
    private val onlineEnrichmentPreferences: OnlineEnrichmentPreferences = OnlineEnrichmentPreferences(
        context.applicationContext,
    ),
    private val modelInstallStore: ModelInstallStore = ModelInstallStore(context.applicationContext),
    private val modelDownloadScheduler: ModelDownloadScheduler = ModelDownloadScheduler(context.applicationContext),
    private val ocrLanguagePackInstallStore: OcrLanguagePackInstallStore =
        OcrLanguagePackInstallStore(context.applicationContext),
    private val ocrLanguagePackDownloadScheduler: OcrLanguagePackDownloadScheduler =
        OcrLanguagePackDownloadScheduler(context.applicationContext),
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
    private val appContext = context.applicationContext
    private val transferEnvelopeCodec = GrayinTransferEnvelopeCodec()
    private val backupStagingStore = EncryptedBackupStagingStore(appContext)
    private val automaticIndexingScheduler = AutomaticIndexingScheduler(appContext)

    suspend fun snapshot(strings: GrayinStrings): GrayinSnapshot = withContext(Dispatchers.IO) {
        val stored = store.loadSnapshot()
        val sourceReferences = stored.sourceReferences
        val events = stored.derivedMemoryEvents
        val localModelStatus = runCatching { localLanguageModel.status() }
            .getOrDefault(LocalModelStatus.UNAVAILABLE)
        GrayinSnapshot(
            sourceRows = connectorRegistry.all.map { connector ->
                sourceRow(
                    connector = connector,
                    sourceReferences = sourceReferences,
                    scanStatus = stored.connectorScanStatuses.firstOrNull { status ->
                        status.connectorId == connector.metadata.connectorId
                    },
                    strings = strings,
                )
            },
            indexingStatus = loadIndexingStatus(strings),
            timelineRows = timelineRows(events, strings),
            placesRows = placeRows(stored.placeClusters, strings),
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
            ocrLanguagePacks = ocrLanguagePackOptions(strings),
        )
    }

    suspend fun indexingStatus(strings: GrayinStrings): IndexingStatusUiState = withContext(Dispatchers.IO) {
        loadIndexingStatus(strings)
    }

    private suspend fun loadIndexingStatus(strings: GrayinStrings): IndexingStatusUiState {
        val queueSnapshot = indexingQueue.snapshot(limit = INDEXING_STATUS_QUEUE_LIMIT)
        val automaticRuntime = automaticIndexingRuntimeStore.loadAutomaticIndexingRuntime()
        return IndexingStatusUiMapper.map(
            queue = queueSnapshot,
            runtime = automaticRuntime,
            sourceName = { connectorId ->
                strings.connectorName(
                    connectorId = connectorId,
                    fallback = connectorRegistry.find(connectorId)?.metadata?.displayName ?: connectorId,
                )
            },
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
                canDownload = entry.downloadConfigured &&
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
            add(strings.localModelApproxSize(strings.formatGigabytes(entry.approxSizeBytes)))
            add(strings.localModelRecommendedRam(entry.recommendedRamGb))
            add(strings.localModelFileName(entry.fileName))
            if (entry.recommended) add(strings.localModelRecommended)
            if (!entry.downloadConfigured) add(strings.localModelDownloadUnavailable)
            if (record.status == ModelDownloadStatus.QUEUED || record.status == ModelDownloadStatus.DOWNLOADING) {
                add(strings.localModelRequiresUnmeteredNetwork)
                record.progressPercent?.let { progress -> add(strings.localModelProgress(progress)) }
            }
            if (record.status == ModelDownloadStatus.FAILED && record.failureCode != null) {
                add(strings.localModelFailure(record.failureCode))
            }
            if (record.status == ModelDownloadStatus.READY && record.installedBytes > 0L) {
                add(strings.localModelInstalledSize(strings.formatGigabytes(record.installedBytes)))
            }
        }
    }

    private suspend fun ocrLanguagePackOptions(strings: GrayinStrings): List<OcrLanguagePackUiState> {
        return OcrLanguagePackCatalog.all().map { entry ->
            ocrLanguagePackDownloadScheduler.reconcile(entry.id)
            val record = ocrLanguagePackInstallStore.recordFor(entry)
            OcrLanguagePackUiState(
                id = entry.id,
                name = strings.ocrLanguagePackName(entry.language),
                status = strings.ocrLanguagePackStatus(record.status),
                detailRows = buildList {
                    add(strings.ocrLanguagePackSize(strings.formatExactDownloadSize(entry.expectedSizeBytes)))
                    add(strings.ocrLanguagePackLicense(entry.licenseLabel))
                    add(strings.ocrLanguagePackCatalogCommit(OcrLanguagePackCatalog.TESSDATA_FAST_COMMIT))
                    if (
                        record.status == OcrLanguagePackStatus.QUEUED ||
                        record.status == OcrLanguagePackStatus.DOWNLOADING
                    ) {
                        add(strings.ocrLanguagePackRequiresUnmeteredNetwork())
                        record.progressPercent?.let { progress ->
                            add(strings.ocrLanguagePackProgress(progress))
                        }
                    }
                    if (record.status == OcrLanguagePackStatus.FAILED) {
                        record.failureCode?.let { failure ->
                            add(strings.ocrLanguagePackFailure(failure))
                        }
                    }
                },
                canDownload = record.status != OcrLanguagePackStatus.READY &&
                    record.status != OcrLanguagePackStatus.QUEUED &&
                    record.status != OcrLanguagePackStatus.DOWNLOADING,
                canCancelDownload = record.status == OcrLanguagePackStatus.QUEUED ||
                    record.status == OcrLanguagePackStatus.DOWNLOADING,
                canDelete = record.status == OcrLanguagePackStatus.READY,
            )
        }
    }

    suspend fun indexLocalFiles(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val result = enqueueAndDrain(IndexConnector(LocalFileMemoryExtractor.CONNECTOR_ID)).singleOrNull()
        singleIndexResult(
            connector = localFilesConnector,
            result = result,
            strings = strings,
            localFiles = true,
        )
    }

    suspend fun indexConnector(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        val result = enqueueAndDrain(IndexConnector(connectorId)).singleOrNull()
        singleIndexResult(
            connector = connector,
            result = result,
            strings = strings,
            localFiles = connectorId == LocalFileMemoryExtractor.CONNECTOR_ID,
        )
    }

    suspend fun indexConnectorDateRange(
        connectorId: String,
        range: ManualIndexDateRange,
        strings: GrayinStrings,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        require(connector.metadata.supportsDateRangeIndexing) {
            "Connector does not support manual date-range indexing."
        }
        val result = enqueueAndDrain(range.toCommand(connectorId, zoneId)).singleOrNull()
        singleIndexResult(
            connector = connector,
            result = result,
            strings = strings,
            localFiles = false,
        )
    }

    suspend fun indexAllEnabledSources(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val results = enqueueAndDrain(IndexNow)
        val scanned = results.filter { result ->
            result.state == IndexingQueueState.COMPLETED ||
                result.skipReason == IndexingSkipReason.NO_INDEXABLE_DATA ||
                result.state == IndexingQueueState.FAILED
        }
        when {
            results.isEmpty() || scanned.isEmpty() -> strings.noSourcesReadyToIndex
            scanned.all { result -> result.state == IndexingQueueState.FAILED } -> strings.indexingFailed
            else -> strings.indexedAllSources(
                eventCount = scanned.sumOf { result -> result.indexedEventCount },
                sourceCount = scanned.size,
                skippedCount = scanned.count { result -> result.state == IndexingQueueState.SKIPPED },
            )
        }
    }

    suspend fun connectConnector(connectorId: String): ConnectorConnectionResult = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        if (connector !is InvokableMemoryConnector) {
            return@withContext ConnectorConnectionResult.Unavailable(connectorId)
        }
        val permissionState = connector.invoke()
        if (!permissionState.permissionGranted) {
            return@withContext ConnectorConnectionResult.PermissionRequired(connectorId)
        }
        store.markConnectorReconsented(connectorId)
        ConnectorConnectionResult.Connected(connectorId, connector.metadata.displayName)
    }

    suspend fun rememberSelectedLocalFile(uri: Uri, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        if (localFilesConnector.rememberSelectedFile(uri)) {
            store.markConnectorReconsented(LocalFileMemoryExtractor.CONNECTOR_ID)
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
        if (!entry.downloadConfigured) return@withContext strings.localModelDownloadUnavailable
        modelInstallStore.selectModel(entry.id)
        modelInstallStore.recordQueued(entry.id)
        modelDownloadScheduler.enqueue(entry.id)
        strings.localModelDownloadQueued(entry.displayName)
    }

    suspend fun downloadOcrLanguagePack(packId: String, strings: GrayinStrings): String =
        withContext(Dispatchers.IO) {
            val entry = OcrLanguagePackCatalog.entry(packId)
                ?: return@withContext strings.ocrLanguagePackActionFailed()
            if (!ocrLanguagePackDownloadScheduler.enqueue(entry.id)) {
                return@withContext strings.ocrLanguagePackActionFailed()
            }
            strings.ocrLanguagePackQueued(strings.ocrLanguagePackName(entry.language))
        }

    suspend fun cancelOcrLanguagePackDownload(packId: String, strings: GrayinStrings): String =
        withContext(Dispatchers.IO) {
            val entry = OcrLanguagePackCatalog.entry(packId)
                ?: return@withContext strings.ocrLanguagePackActionFailed()
            if (!ocrLanguagePackDownloadScheduler.cancel(entry.id)) {
                return@withContext strings.ocrLanguagePackActionFailed()
            }
            strings.ocrLanguagePackCanceled(strings.ocrLanguagePackName(entry.language))
        }

    suspend fun deleteOcrLanguagePack(packId: String, strings: GrayinStrings): String =
        withContext(Dispatchers.IO) {
            val entry = OcrLanguagePackCatalog.entry(packId)
                ?: return@withContext strings.ocrLanguagePackActionFailed()
            if (!ocrLanguagePackDownloadScheduler.delete(entry.id)) {
                return@withContext strings.ocrLanguagePackActionFailed()
            }
            strings.ocrLanguagePackDeleted(strings.ocrLanguagePackName(entry.language))
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

    suspend fun updateOnlineEnrichment(enabled: Boolean, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        onlineEnrichmentPreferences.setEnabled(enabled)
        strings.onlineEnrichmentSaved(enabled)
    }

    suspend fun prepareEncryptedExport(
        password: CharArray,
        createdAt: Instant = Instant.now(),
    ): TransferResult<PreparedEncryptedExport> = withContext(Dispatchers.IO) {
        var ciphertext: ByteArray? = null
        try {
            when (
                val encrypted = transferEnvelopeCodec.encrypt(
                    payload = TransferPayload(
                        createdAt = createdAt,
                        producer = TransferProducerMetadata(
                            applicationId = appContext.packageName,
                            versionCode = installedVersionCode(),
                            storeSchemaVersion = SqlCipherLocalMemoryStore.CURRENT_SCHEMA_VERSION,
                        ),
                        snapshot = store.loadSnapshot(),
                    ),
                    password = password,
                )
            ) {
                is TransferResult.Success -> ciphertext = encrypted.value
                is TransferResult.Failure -> return@withContext encrypted
            }
            when (val staged = backupStagingStore.stageCiphertext(checkNotNull(ciphertext))) {
                is BackupFileResult.Success -> TransferResult.Success(
                    PreparedEncryptedExport(
                        token = staged.value.token,
                        suggestedFileName = BackupDocumentContractPolicy.suggestedFileName(createdAt),
                        sizeBytes = staged.value.sizeBytes,
                    ),
                )

                is BackupFileResult.Failed -> transferFileFailure(staged.code, importing = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TransferResult.Failure(TransferFailure(TransferFailureCode.SOURCE_IO_FAILED))
        } finally {
            ciphertext?.fill(0)
            password.fill('\u0000')
        }
    }

    suspend fun writePreparedEncryptedExport(
        token: String,
        destination: Uri,
    ): TransferResult<Long> = withContext(Dispatchers.IO) {
        try {
            when (val written = backupStagingStore.writeToDocument(token, destination)) {
                is BackupFileResult.Success -> TransferResult.Success(written.value)
                is BackupFileResult.Failed -> transferFileFailure(written.code, importing = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            TransferResult.Failure(TransferFailure(TransferFailureCode.DESTINATION_IO_FAILED))
        } finally {
            withContext(NonCancellable) { backupStagingStore.discard(token) }
        }
    }

    suspend fun stageEncryptedImport(source: Uri): TransferResult<StagedEncryptedImport> =
        withContext(Dispatchers.IO) {
            when (val staged = backupStagingStore.stageFromDocument(source)) {
                is BackupFileResult.Success -> TransferResult.Success(
                    StagedEncryptedImport(
                        token = staged.value.token,
                        sizeBytes = staged.value.sizeBytes,
                    ),
                )

                is BackupFileResult.Failed -> transferFileFailure(staged.code, importing = true)
            }
        }

    suspend fun importStagedEncryptedBackup(
        token: String,
        password: CharArray,
    ): TransferResult<ImportedBackupSummary> = withContext(Dispatchers.IO) {
        val ciphertext = when (val staged = backupStagingStore.readCiphertext(token)) {
            is BackupFileResult.Success -> staged.value
            is BackupFileResult.Failed -> {
                password.fill('\u0000')
                withContext(NonCancellable) { backupStagingStore.discard(token) }
                return@withContext transferFileFailure(staged.code, importing = true)
            }
        }
        val payload = try {
            when (val decrypted = transferEnvelopeCodec.decrypt(ciphertext, password)) {
                is TransferResult.Success -> decrypted.value
                is TransferResult.Failure -> {
                    if (!BackupTransferPolicy.retainImportStageAfter(decrypted.failure.code)) {
                        backupStagingStore.discard(token)
                    }
                    return@withContext decrypted
                }
            }
        } finally {
            ciphertext.fill(0)
            password.fill('\u0000')
        }

        try {
            automaticIndexingScheduler.disableForImport()
            onlineEnrichmentPreferences.setEnabled(false)
            resetConnectorConsentForImport()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext TransferResult.Failure(
                TransferFailure(TransferFailureCode.CONSENT_RESET_FAILED),
            )
        }

        val trustedConnectorIds = connectorRegistry.all
            .mapTo(linkedSetOf()) { connector -> connector.metadata.connectorId }
        val imported = try {
            store.replaceDerivedDataFromImport(
                snapshot = payload.snapshot,
                trustedConnectorIds = trustedConnectorIds,
                importedAt = Instant.now(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext TransferResult.Failure(
                TransferFailure(TransferFailureCode.STORE_TRANSACTION_FAILED),
            )
        }
        withContext(NonCancellable) { backupStagingStore.discard(token) }
        TransferResult.Success(
            ImportedBackupSummary(
                derivedMemoryEventCount = imported.derivedMemoryEventCount,
                connectorCount = imported.connectorsRequiringReconsent.size,
            ),
        )
    }

    suspend fun discardEncryptedBackupStage(token: String) {
        backupStagingStore.discard(token)
    }

    suspend fun cleanupStaleEncryptedBackupStages() {
        backupStagingStore.cleanupStale()
    }

    private suspend fun resetConnectorConsentForImport() {
        connectorRegistry.all
            .sortedBy { connector ->
                if (connector.metadata.connectorId == NotificationConnector.CONNECTOR_ID) 0 else 1
            }
            .forEach { connector -> connector.revoke() }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun <T> transferFileFailure(
        code: BackupFileFailureCode,
        importing: Boolean,
    ): TransferResult<T> {
        val transferCode = when (code) {
            BackupFileFailureCode.CIPHERTEXT_TOO_LARGE -> TransferFailureCode.TOO_LARGE
            BackupFileFailureCode.EMPTY_CIPHERTEXT,
            BackupFileFailureCode.CIPHERTEXT_SIZE_MISMATCH,
            BackupFileFailureCode.INVALID_CIPHERTEXT_FORMAT,
            -> TransferFailureCode.INVALID_FORMAT

            BackupFileFailureCode.UNSUPPORTED_CIPHERTEXT_VERSION -> TransferFailureCode.UNSUPPORTED_VERSION

            else -> if (importing) {
                TransferFailureCode.SOURCE_IO_FAILED
            } else {
                TransferFailureCode.DESTINATION_IO_FAILED
            }
        }
        return TransferResult.Failure(TransferFailure(transferCode))
    }

    suspend fun deleteLocalFileData(strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val deleteResult = store.deleteConnectorData(LocalFileMemoryExtractor.CONNECTOR_ID)
        localFilesConnector.deleteDerivedData(
            ConnectorDeleteRequest(connectorId = LocalFileMemoryExtractor.CONNECTOR_ID),
        )
        strings.deletedLocalFileEvents(deleteResult.deletedDerivedMemoryEventIds.size)
    }

    suspend fun deleteConnectorData(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val deleteResult = store.deleteConnectorData(connectorId)
        connectorFor(connectorId).deleteDerivedData(ConnectorDeleteRequest(connectorId = connectorId))
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
        store.deleteConnectorData(revokeResult.connectorId, requireReconsent = true)
        strings.revokedLocalFiles
    }

    suspend fun revokeConnector(connectorId: String, strings: GrayinStrings): String = withContext(Dispatchers.IO) {
        val connector = connectorFor(connectorId)
        val revokeResult = connector.revoke()
        store.deleteConnectorData(revokeResult.connectorId, requireReconsent = true)
        if (connectorId == LocalFileMemoryExtractor.CONNECTOR_ID) {
            strings.revokedLocalFiles
        } else {
            strings.revokedConnector(strings.connectorName(connectorId, connector.metadata.displayName))
        }
    }

    private fun connectorFor(connectorId: String): MemoryConnector {
        return connectorRegistry.require(connectorId)
    }

    private suspend fun enqueueAndDrain(command: IndexingCommand): List<IndexingQueueItem> {
        val enqueued = indexingExecutor.enqueue(command = command, trigger = IndexingTrigger.MANUAL)
        if (enqueued.isEmpty()) return emptyList()
        val requestedIds = enqueued.mapTo(mutableSetOf()) { item -> item.id }
        return indexingExecutor.drain(
            trigger = IndexingTrigger.MANUAL,
            leaseOwner = MANUAL_INDEXING_LEASE_OWNER,
            maxTasks = MAX_MANUAL_DRAIN_TASKS,
        ).filter { item -> item.id in requestedIds }
    }

    private fun singleIndexResult(
        connector: MemoryConnector,
        result: IndexingQueueItem?,
        strings: GrayinStrings,
        localFiles: Boolean,
    ): String {
        if (result == null) return strings.indexing
        return when (result.state) {
            IndexingQueueState.COMPLETED -> if (localFiles) {
                strings.indexedLocalFileEvents(result.indexedEventCount)
            } else {
                strings.indexedConnectorEvents(
                    connectorName = strings.connectorName(
                        connector.metadata.connectorId,
                        connector.metadata.displayName,
                    ),
                    count = result.indexedEventCount,
                )
            }

            IndexingQueueState.SKIPPED -> when (result.skipReason) {
                IndexingSkipReason.MISSING_PERMISSION -> strings.sourcePermissionDenied
                IndexingSkipReason.NO_INDEXABLE_DATA -> if (localFiles) {
                    strings.noLocalFilesIndexed
                } else {
                    strings.manualIndexingSkipped(result.skipReason)
                }

                else -> strings.manualIndexingSkipped(result.skipReason)
            }

            IndexingQueueState.FAILED -> strings.manualIndexingFailed(result.failureCode)

            IndexingQueueState.PENDING,
            IndexingQueueState.RUNNING,
            -> strings.indexing
        }
    }

    suspend fun ask(query: String, strings: GrayinStrings): AnswerUiState = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyAnswer(strings.enterMemoryQuestion, strings)

        val stored = store.loadSnapshot()
        val planning = ScopedQueryPlanning.resolve(
            query = trimmedQuery,
            events = stored.derivedMemoryEvents,
            planner = queryPlanner,
        )
        val plan = planning.plan
        val evidenceItems = evidenceFor(trimmedQuery, plan, planning.events)
        val usedCitationIds = evidenceItems.flatMap { it.citationIds }.toSet()
        val citations = stored.citations.filter { it.id in usedCitationIds }
        val plannedMissingSources = MissingEvidenceResolver.resolve(
            plan = plan,
            hasEvidence = evidenceItems.isNotEmpty(),
            noMatchingEvidenceExplanation = strings.noAnswerAvailable,
        )
        val missingSources = ConnectorScanMissingResolver.merge(
            plan = plan,
            plannedMissingSources = plannedMissingSources,
            scanStatuses = stored.connectorScanStatuses,
            issueExplanation = strings::connectorScanIssue,
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
            confidence = strings.confidenceLabel(answer.confidence),
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
            confidence = strings.confidenceLabel(confidence),
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

    private fun evidenceFor(
        query: String,
        plan: QueryPlan,
        events: List<DerivedMemoryEvent>,
    ): List<EvidenceItem> {
        return EvidenceEventSelector.select(query, plan, events, MAX_EVIDENCE_ITEMS).map { event ->
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

    private suspend fun sourceRow(
        connector: MemoryConnector,
        sourceReferences: List<SourceReference>,
        scanStatus: ConnectorScanStatus?,
        strings: GrayinStrings,
    ): ConnectorUiState {
        val state = connector.currentState()
        val permissionState = connector.permissionState()
        val connectorId = connector.metadata.connectorId
        val isLocalFiles = connectorId == LocalFileMemoryExtractor.CONNECTOR_ID
        val connectorName = strings.connectorName(connectorId, connector.metadata.displayName)
        val reconsentRequired = store.isConnectorReconsentRequired(connectorId)
        return ConnectorUiState(
            id = connectorId,
            name = connectorName,
            status = sourceStatus(
                state = state,
                permissionState = permissionState,
                hasStoredSources = sourceReferences.any { source -> source.connectorId == connectorId },
                scanStatus = scanStatus,
                reconsentRequired = reconsentRequired,
                strings = strings,
            ),
            sensitivity = strings.sensitivityLabel(connector.metadata.sensitivity),
            canInvoke = !isLocalFiles && permissionState.canRequestPermission &&
                (!state.consentEnabled || !permissionState.permissionGranted || reconsentRequired),
            requiredPermissions = permissionState.requiredPlatformPermissions,
            canAdd = isLocalFiles,
            canIndex = state.enabled && (isLocalFiles || permissionState.permissionGranted) && !reconsentRequired &&
                connector.metadata.indexingMode != ConnectorIndexingMode.EVENT_DRIVEN,
            canRevoke = state.consentEnabled,
            canDelete = sourceReferences.any { it.connectorId == connectorId },
            notificationAllowedPackages = if (connectorId == NotificationConnector.CONNECTOR_ID) {
                notificationAllowlist.load().toList()
            } else {
                emptyList()
            },
            onlineEnrichmentEnabled = if (connectorId == LocationConnector.CONNECTOR_ID) {
                onlineEnrichmentPreferences.isEnabled()
            } else {
                null
            },
            detailRows = strings.connectorScanDetailRows(scanStatus),
            supportsDateRangeIndexing = connector.metadata.supportsDateRangeIndexing,
            lastRequestedScanRange = if (scanStatus?.scopeFrom != null && scanStatus.scopeUntil != null) {
                IndexedDateRangePresentation(
                    from = scanStatus.scopeFrom,
                    untilExclusive = scanStatus.scopeUntil,
                )
            } else {
                null
            },
        )
    }

    private fun sourceStatus(
        state: ConnectorState,
        permissionState: ConnectorPermissionState,
        hasStoredSources: Boolean,
        scanStatus: ConnectorScanStatus?,
        reconsentRequired: Boolean,
        strings: GrayinStrings,
    ): String {
        return when {
            reconsentRequired -> strings.reconnectionRequired
            state.consentEnabled && !permissionState.permissionGranted -> strings.sourcePermissionDenied
            hasStoredSources &&
                (scanStatus?.processingState == ProcessingState.COMPLETED ||
                    state.processingState == ProcessingState.COMPLETED) -> strings.indexed
            state.enabled -> strings.selected
            !permissionState.canRequestPermission && !permissionState.permissionGranted -> strings.sourcePermissionDenied
            else -> strings.off
        }
    }

    private fun timelineRows(events: List<DerivedMemoryEvent>, strings: GrayinStrings): List<String> {
        if (events.isEmpty()) return listOf(strings.noDerivedEvents)
        return DerivedMemoryPresentationMapper.timeline(events)
            .take(MAX_TIMELINE_ROWS)
            .map(strings::timelineRow)
    }

    private fun placeRows(
        places: List<ai.grayin.core.model.PlaceCluster>,
        strings: GrayinStrings,
    ): List<String> {
        if (places.isEmpty()) return listOf(strings.noPlaceClusters)
        return DerivedMemoryPresentationMapper.places(places)
            .take(MAX_PLACE_ROWS)
            .map(strings::placeRow)
    }

    private fun emptyAnswer(message: String, strings: GrayinStrings): AnswerUiState {
        return AnswerUiState(
            answer = message,
            confidence = strings.confidenceLabel(ConfidenceLevel.UNKNOWN),
            evidenceRows = listOf(strings.noCitedEvidence),
            missingRows = listOf(strings.askFromIndexedEvidence),
        )
    }

    private fun missingSourceRow(missingSource: MissingSource, strings: GrayinStrings): String {
        val explanation = missingSource.issueCode?.let(strings::connectorScanIssue) ?: if (
            missingSource.explanation.startsWith("Required capability ") ||
            missingSource.explanation.startsWith("Optional capability ")
        ) {
            strings.capabilityUnavailable(strings.memoryCapabilityLabel(missingSource.capability))
        } else {
            missingSource.explanation
        }
        return "${strings.memoryCapabilityLabel(missingSource.capability)}: $explanation"
    }

    private companion object {
        const val INDEXING_STATUS_QUEUE_LIMIT = 20
        const val MAX_EVIDENCE_ITEMS = 8
        const val MAX_TIMELINE_ROWS = 30
        const val MAX_PLACE_ROWS = 30
        const val MAX_MANUAL_DRAIN_TASKS = 64
        const val MANUAL_INDEXING_LEASE_OWNER = "manual-ui"
    }
}

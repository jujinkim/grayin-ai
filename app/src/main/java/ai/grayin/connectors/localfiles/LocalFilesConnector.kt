package ai.grayin.connectors.localfiles

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import ai.grayin.connectors.localfiles.document.BoundDocumentProcessingClient
import ai.grayin.connectors.localfiles.document.DocumentProcessingClient
import ai.grayin.connectors.localfiles.document.PdfResourceLimits
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.connector.missingSourceIdentity
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.security.AndroidKeystoreSourceIdentityHasher
import ai.grayin.core.security.SourceIdentityHasher
import java.io.Reader
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LocalFilesConnector internal constructor(
    context: Context,
    private val identityHasher: SourceIdentityHasher = AndroidKeystoreSourceIdentityHasher(),
    private val extractor: LocalFileMemoryExtractor = LocalFileMemoryExtractor(),
    private val documentProcessingClient: DocumentProcessingClient = BoundDocumentProcessingClient(context),
    private val pdfExtractor: LocalPdfMemoryExtractor = LocalPdfMemoryExtractor(identityHasher),
    private val clock: Clock = Clock.systemUTC(),
    private val permissionStore: LocalDocumentPermissionStore = AndroidLocalDocumentPermissionStore(
        context.applicationContext.contentResolver,
    ),
) : MemoryConnector {
    private val context = context.applicationContext

    override val metadata: ConnectorMetadata = CONNECTOR_METADATA

    suspend fun rememberSelectedFile(uri: Uri): Boolean {
        val documentType = withTimeout(SELECTION_METADATA_TIMEOUT_MILLIS) {
            scanDocumentTypeFor(uri)
        }
        if (documentType == LocalDocumentType.UNSUPPORTED) return false
        val identityHmac = documentIdentityHmac(uri)
        return withContext(Dispatchers.IO) {
            synchronized(PREFS_LOCK) {
                val selected = selectedIdentityHmacsLocked().toMutableSet()
                val alreadySelected = identityHmac in selected
                if (!alreadySelected && selected.size >= MAX_SELECTED_DOCUMENTS) {
                    return@synchronized false
                }
                if (!alreadySelected) {
                    selected += identityHmac
                    val markerCommitted = prefs().edit()
                        .putStringSet(KEY_SELECTED_IDENTITY_HMACS, selected)
                        .commit()
                    if (!markerCommitted) return@synchronized false
                }
                try {
                    permissionStore.persistRead(uri)
                } catch (_: SecurityException) {
                    rollbackSelectionMarker(identityHmac, alreadySelected)
                    return@synchronized false
                } catch (_: RuntimeException) {
                    rollbackSelectionMarker(identityHmac, alreadySelected)
                    return@synchronized false
                }
                true
            }
        }
    }

    override suspend fun currentState(): ConnectorState {
        val selected = selectedIdentityHmacs()
        return ConnectorState(
            connectorId = metadata.connectorId,
            displayName = metadata.displayName,
            enabled = selected.isNotEmpty(),
            consentEnabled = selected.isNotEmpty(),
            availability = if (selected.isEmpty()) SourceAvailability.DISABLED else SourceAvailability.AVAILABLE,
            permissionGranted = selected.isNotEmpty(),
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = when {
                selected.isEmpty() -> ProcessingState.SKIPPED
                else -> ProcessingState.STALE
            },
        )
    }

    override suspend fun permissionState(): ConnectorPermissionState {
        val selected = selectedIdentityHmacs()
        return ConnectorPermissionState(
            connectorId = metadata.connectorId,
            availability = if (selected.isEmpty()) SourceAvailability.DISABLED else SourceAvailability.AVAILABLE,
            permissionGranted = selected.isNotEmpty(),
            canRequestPermission = true,
            requiredPlatformPermissions = emptyList(),
            explanation = "Select Text, Markdown, or PDF documents with the Android document picker.",
        )
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        return withTimeout(PdfResourceLimits.SCAN_TIMEOUT_MILLIS) {
            scanSelectedDocuments(scope)
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult = withContext(Dispatchers.IO) {
        synchronized(PREFS_LOCK) {
            permissionStore.persistedReadUris().distinct().forEach { uri ->
                runCatching { permissionStore.releaseRead(uri) }
            }
            check(permissionStore.persistedReadUris().isEmpty()) {
                "Could not verify that all local document permissions were revoked."
            }
            check(prefs().edit().clear().commit()) { "Could not clear local document access state." }
        }
        ConnectorRevokeResult(
            connectorId = metadata.connectorId,
            revokedAt = clock.instant(),
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = metadata.connectorId),
        )
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = clock.instant(),
        )
    }

    private suspend fun scanSelectedDocuments(scope: ConnectorScanScope): ConnectorScanResult {
        val scannedAt = clock.instant()
        val allSelected = selectedIdentityHmacs().sorted()
        if (allSelected.isEmpty()) {
            return scanResult(
                scannedAt = scannedAt,
                scope = scope,
                issueCodes = setOf(ConnectorScanIssueCode.NO_LOCAL_DOCUMENTS_SELECTED),
            )
        }
        val selected = allSelected.take(MAX_SELECTED_DOCUMENTS)
        val issueCodes = mutableSetOf<ConnectorScanIssueCode>()
        if (selected.size < allSelected.size) {
            issueCodes += ConnectorScanIssueCode.LOCAL_DOCUMENT_SELECTION_LIMIT_REACHED
            issueCodes += ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX
        }

        val resolvedUris = resolvedPersistedUris(selected.toSet())
        val sourceReferences = mutableListOf<SourceReference>()
        val derivedEvents = mutableListOf<DerivedMemoryEvent>()
        val citations = mutableListOf<MemoryCitation>()
        var derivedPdfPageCount = 0

        selected.forEach { identityHmac ->
            currentCoroutineContext().ensureActive()
            val uri = resolvedUris[identityHmac]
            if (uri == null) {
                issueCodes += ConnectorScanIssueCode.LOCAL_DOCUMENT_PERMISSION_REVOKED
                return@forEach
            }
            val documentType = scanDocumentTypeFor(uri)
            when (documentType) {
                LocalDocumentType.TEXT,
                LocalDocumentType.MARKDOWN,
                -> {
                    val sourceKind = if (documentType == LocalDocumentType.MARKDOWN) {
                        SourceKind.MARKDOWN_NOTE
                    } else {
                        SourceKind.LOCAL_FILE
                    }
                    val boundedText = try {
                        readTextDocument(uri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        issueCodes += ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED
                        return@forEach
                    }
                    val result = extractor.extract(
                        metadata = LocalFileMetadata(
                            identityHmac = identityHmac,
                            sourceKind = sourceKind,
                            observedAt = scannedAt,
                        ),
                        text = boundedText.value,
                    )
                    sourceReferences += result.sourceReference
                    derivedEvents += result.derivedEvent
                    citations += result.citation
                    if (boundedText.truncated) {
                        issueCodes += ConnectorScanIssueCode.LOCAL_DOCUMENT_TEXT_LIMIT_REACHED
                    }
                }

                LocalDocumentType.PDF -> {
                    val remainingCapacity = PdfResourceLimits.MAX_DERIVED_PAGE_SIGNALS_PER_SCAN - derivedPdfPageCount
                    if (remainingCapacity <= 0) {
                        issueCodes += ConnectorScanIssueCode.DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED
                        issueCodes += ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX
                        return@forEach
                    }
                    val fragment = processPdf(
                        uri = uri,
                        identityHmac = identityHmac,
                        observedAt = scannedAt,
                        remainingOutputCapacity = remainingCapacity,
                    )
                    sourceReferences += fragment.sourceReferences
                    derivedEvents += fragment.derivedEvents
                    citations += fragment.citations
                    issueCodes += fragment.issueCodes
                    derivedPdfPageCount += fragment.derivedEvents.size
                }

                LocalDocumentType.UNSUPPORTED -> {
                    issueCodes += ConnectorScanIssueCode.LOCAL_DOCUMENT_TYPE_UNSUPPORTED
                }
            }
        }

        return scanResult(
            scannedAt = scannedAt,
            scope = scope,
            sourceReferences = sourceReferences,
            derivedEvents = derivedEvents,
            citations = citations,
            issueCodes = issueCodes,
        )
    }

    private suspend fun processPdf(
        uri: Uri,
        identityHmac: String,
        observedAt: Instant,
        remainingOutputCapacity: Int,
    ): LocalDocumentExtractionResult {
        val descriptor = try {
            runCancellableSafCall(
                discardResult = { opened -> opened?.let { runCatching(it::close) } },
            ) { cancellationSignal ->
                context.contentResolver.openFileDescriptor(uri, "r", cancellationSignal)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return LocalDocumentExtractionResult(
            issueCodes = setOf(ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED),
        )
        return try {
            val runtimeResult = documentProcessingClient.process(descriptor)
            pdfExtractor.extract(
                documentIdentityHmac = identityHmac,
                result = runtimeResult,
                observedAt = observedAt,
                remainingOutputCapacity = remainingOutputCapacity,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LocalDocumentExtractionResult(
                issueCodes = setOf(ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED),
            )
        } finally {
            runCatching { descriptor.close() }
        }
    }

    private fun scanResult(
        scannedAt: Instant,
        scope: ConnectorScanScope,
        sourceReferences: List<SourceReference> = emptyList(),
        derivedEvents: List<DerivedMemoryEvent> = emptyList(),
        citations: List<MemoryCitation> = emptyList(),
        issueCodes: Set<ConnectorScanIssueCode>,
    ): ConnectorScanResult {
        val missingSources = issueCodes
            .sortedBy(ConnectorScanIssueCode::storageKey)
            .flatMap { issueCode -> missingSources(issueCode) }
            .distinctBy(::missingSourceIdentity)
        return ConnectorScanResult(
            connectorId = metadata.connectorId,
            processingState = if (derivedEvents.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = sourceReferences,
            derivedEvents = derivedEvents,
            citations = citations,
            missingSources = missingSources,
            scopeFrom = scope.from,
            scopeUntil = scope.until,
            replaceExistingConnectorData = true,
            scannedAt = scannedAt,
        )
    }

    private suspend fun scanDocumentTypeFor(uri: Uri): LocalDocumentType {
        val metadata = try {
            runCancellableSafCall { cancellationSignal ->
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    Bundle.EMPTY,
                    cancellationSignal,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    fun stringValue(column: String): String? {
                        val index = cursor.getColumnIndex(column)
                        return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
                    }
                    LocalDocumentMetadata(
                        mimeType = stringValue(DocumentsContract.Document.COLUMN_MIME_TYPE),
                        displayName = stringValue(OpenableColumns.DISPLAY_NAME),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return LocalDocumentTypeResolver.resolve(
            mimeType = metadata?.mimeType,
            displayName = (metadata?.displayName ?: uri.lastPathSegment)
                ?.takeLast(MAX_TRANSIENT_DISPLAY_NAME_CHARS),
        )
    }

    private suspend fun readTextDocument(uri: Uri): BoundedLocalText {
        return runCancellableSafCall { cancellationSignal ->
            val descriptor = checkNotNull(
                context.contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal),
            )
            readBoundedUtf8(descriptor, cancellationSignal, MAX_TRANSIENT_CHARS)
        }
    }

    private suspend fun resolvedPersistedUris(selected: Set<String>): Map<String, Uri> {
        val persistedUris = runCancellableSafCall {
            permissionStore.persistedReadUris()
        }
        return persistedUris
            .asSequence()
            .map { uri -> documentIdentityHmac(uri) to uri }
            .filter { (hmac, _) -> hmac in selected }
            .sortedBy { (hmac, _) -> hmac }
            .toMap()
    }

    private fun selectedIdentityHmacs(): Set<String> = synchronized(PREFS_LOCK) {
        selectedIdentityHmacsLocked()
    }

    private fun selectedIdentityHmacsLocked(): Set<String> {
        val preferences = prefs()
        val current = preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { value -> value.matches(SAFE_HMAC) }
        val hasLegacyUris = preferences.contains(KEY_LEGACY_SELECTED_URIS)
        val legacyUris = preferences.getStringSet(KEY_LEGACY_SELECTED_URIS, emptySet()).orEmpty()
        val migrated = legacyUris.mapTo(current) { encodedUri ->
            documentIdentityHmac(Uri.parse(encodedUri))
        }
        val storedCurrent = preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet()).orEmpty()
        if (hasLegacyUris || storedCurrent != migrated) {
            val editor = preferences.edit()
                .putStringSet(KEY_SELECTED_IDENTITY_HMACS, migrated)
                .remove(KEY_LEGACY_SELECTED_URIS)
            if (hasLegacyUris) editor.remove(KEY_LEGACY_LAST_INDEXED_AT)
            check(editor.commit()) { "Could not migrate local document identity state." }
        }
        return migrated.toSet()
    }

    private fun documentIdentityHmac(uri: Uri): String {
        return identityHasher.hmac(DOCUMENT_IDENTITY_NAMESPACE, uri.toString())
    }

    private fun rollbackSelectionMarker(identityHmac: String, alreadySelected: Boolean) {
        if (alreadySelected) return
        val selected = selectedIdentityHmacsLocked().toMutableSet().apply { remove(identityHmac) }
        check(
            prefs().edit()
                .putStringSet(KEY_SELECTED_IDENTITY_HMACS, selected)
                .commit(),
        ) { "Could not roll back local document selection state." }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class LocalDocumentMetadata(
        val mimeType: String?,
        val displayName: String?,
    )

    private fun missingSources(issueCode: ConnectorScanIssueCode): List<MissingSource> {
        val availability = when (issueCode) {
            ConnectorScanIssueCode.NO_LOCAL_DOCUMENTS_SELECTED -> SourceAvailability.DISABLED
            ConnectorScanIssueCode.LOCAL_DOCUMENT_PERMISSION_REVOKED -> SourceAvailability.DENIED
            ConnectorScanIssueCode.LOCAL_DOCUMENT_TYPE_UNSUPPORTED,
            ConnectorScanIssueCode.DOCUMENT_FILE_TOO_LARGE,
            ConnectorScanIssueCode.DOCUMENT_SIZE_UNKNOWN,
            ConnectorScanIssueCode.DOCUMENT_NOT_SEEKABLE,
            ConnectorScanIssueCode.PDF_PAGE_LIMIT_EXCEEDED,
            ConnectorScanIssueCode.PDF_PASSWORD_REQUIRED,
            ConnectorScanIssueCode.PDF_MALFORMED,
            ConnectorScanIssueCode.PDF_PAGE_DIMENSIONS_UNSUPPORTED,
            -> SourceAvailability.UNSUPPORTED

            else -> SourceAvailability.NOT_INDEXED
        }
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = issueCode.defaultEnglish,
                connectorId = metadata.connectorId,
                issueCode = issueCode,
            )
        }
    }

    companion object {
        val CONNECTOR_METADATA = ConnectorMetadata(
            connectorId = LocalFileMemoryExtractor.CONNECTOR_ID,
            displayName = "Local Files",
            sourceKinds = setOf(SourceKind.LOCAL_FILE, SourceKind.MARKDOWN_NOTE, SourceKind.PDF_PAGE),
            connectorCapabilities = setOf(ConnectorCapability.LOCAL_FILES),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT),
            indexingMode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            defaultEnabled = false,
            sensitivity = SensitivityLevel.HIGH,
        )

        const val MAX_SELECTED_DOCUMENTS = 128
        private const val PREFS_NAME = "grayin_local_files"
        private const val KEY_SELECTED_IDENTITY_HMACS = "selected_source_hmacs_v1"
        private const val KEY_LEGACY_SELECTED_URIS = "selected_uris"
        private const val KEY_LEGACY_LAST_INDEXED_AT = "last_indexed_at"
        private const val DOCUMENT_IDENTITY_NAMESPACE = "local-document-v1"
        private const val MAX_TRANSIENT_CHARS = 64 * 1024
        private const val MAX_TRANSIENT_DISPLAY_NAME_CHARS = 512
        private const val SELECTION_METADATA_TIMEOUT_MILLIS = 15_000L
        private val SAFE_HMAC = Regex("[a-f0-9]{64}")
        private val PREFS_LOCK = Any()
    }
}

internal fun readBoundedUtf8(
    descriptor: AssetFileDescriptor,
    cancellationSignal: CancellationSignal,
    maxChars: Int,
): BoundedLocalText {
    return descriptor.use { asset ->
        val parcelDescriptor = asset.parcelFileDescriptor
        cancellationSignal.setOnCancelListener {
            runCatching { parcelDescriptor.closeWithError("Local document read canceled.") }
        }
        try {
            asset.createInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readTextLimited(maxChars)
            }
        } finally {
            cancellationSignal.setOnCancelListener(null)
        }
    }
}

internal fun Reader.readTextLimited(maxChars: Int): BoundedLocalText {
    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
    val builder = StringBuilder()
    while (builder.length < maxChars) {
        val remaining = maxChars - builder.length
        val readCount = read(buffer, 0, minOf(buffer.size, remaining))
        if (readCount <= 0) return BoundedLocalText(builder.toString(), truncated = false)
        builder.append(buffer, 0, readCount)
    }
    return BoundedLocalText(
        value = builder.toString(),
        truncated = read() >= 0,
    )
}

internal data class BoundedLocalText(
    val value: String,
    val truncated: Boolean,
)

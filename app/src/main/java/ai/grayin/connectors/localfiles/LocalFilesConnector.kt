package ai.grayin.connectors.localfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import java.io.Reader
import java.time.Instant

class LocalFilesConnector(
    private val context: Context,
    private val extractor: LocalFileMemoryExtractor = LocalFileMemoryExtractor(),
) : MemoryConnector {
    override val metadata: ConnectorMetadata = CONNECTOR_METADATA

    fun rememberSelectedFile(uri: Uri): Boolean {
        if (!isSupported(uri)) return false
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            return false
        }
        val updated = selectedUriStrings() + uri.toString()
        prefs().edit().putStringSet(KEY_SELECTED_URIS, updated).apply()
        return true
    }

    override suspend fun currentState(): ConnectorState {
        val selected = selectedUriStrings()
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = metadata.connectorId,
            displayName = metadata.displayName,
            enabled = selected.isNotEmpty(),
            availability = if (selected.isEmpty()) SourceAvailability.DISABLED else SourceAvailability.AVAILABLE,
            permissionGranted = selected.isNotEmpty() && selected.all { hasPersistedPermission(Uri.parse(it)) },
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = when {
                selected.isEmpty() -> ProcessingState.SKIPPED
                lastIndexedAt != null -> ProcessingState.COMPLETED
                else -> ProcessingState.STALE
            },
            lastIndexedAt = lastIndexedAt,
        )
    }

    override suspend fun permissionState(): ConnectorPermissionState {
        val selected = selectedUriStrings()
        val granted = selected.isNotEmpty() && selected.all { hasPersistedPermission(Uri.parse(it)) }
        return ConnectorPermissionState(
            connectorId = metadata.connectorId,
            availability = when {
                selected.isEmpty() -> SourceAvailability.DISABLED
                granted -> SourceAvailability.AVAILABLE
                else -> SourceAvailability.DENIED
            },
            permissionGranted = granted,
            canRequestPermission = true,
            requiredPlatformPermissions = emptyList(),
            explanation = "Select .txt or .md files with the Android document picker.",
        )
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        val selected = selectedUriStrings().map(Uri::parse)
        if (selected.isEmpty()) {
            return ConnectorScanResult(
                connectorId = metadata.connectorId,
                processingState = ProcessingState.SKIPPED,
                missingSources = missingSources(SourceAvailability.DISABLED, "No local text or Markdown files selected."),
                scannedAt = now,
            )
        }

        val results = selected.mapNotNull { uri -> scanUri(uri, now) }
        val missing = selected.flatMap { uri ->
            when {
                !hasPersistedPermission(uri) -> {
                    missingSources(SourceAvailability.DENIED, "Read access was revoked for selected file.")
                }

                !isSupported(uri) -> {
                    missingSources(SourceAvailability.UNSUPPORTED, "Only .txt and .md files are supported in this milestone.")
                }

                else -> emptyList()
            }
        }
        val state = if (results.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED
        return ConnectorScanResult(
            connectorId = metadata.connectorId,
            processingState = state,
            sourceReferences = results.map { it.sourceReference },
            derivedEvents = results.map { it.derivedEvent },
            citations = results.map { it.citation },
            missingSources = missing,
            scannedAt = now,
        )
    }

    override suspend fun onScanStored(scanResult: ConnectorScanResult) {
        if (scanResult.processingState == ProcessingState.COMPLETED) {
            prefs().edit().putString(KEY_LAST_INDEXED_AT, scanResult.scannedAt.toString()).apply()
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        selectedUriStrings().map(Uri::parse).forEach { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
        prefs().edit().clear().apply()
        return ConnectorRevokeResult(
            connectorId = metadata.connectorId,
            revokedAt = Instant.now(),
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = metadata.connectorId),
        )
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }

    private fun scanUri(uri: Uri, observedAt: Instant): LocalFileExtractionResult? {
        if (!hasPersistedPermission(uri) || !isSupported(uri)) return null
        val metadata = metadataFor(uri, observedAt)
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.readTextLimited(MAX_TRANSIENT_CHARS)
        } ?: return null
        return extractor.extract(metadata, text)
    }

    private fun metadataFor(uri: Uri, observedAt: Instant): LocalFileMetadata {
        var displayName = uri.lastPathSegment ?: "selected-file"
        var sizeBytes: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0 && !cursor.isNull(displayNameIndex)) {
                        displayName = cursor.getString(displayNameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        return LocalFileMetadata(
            uri = uri.toString(),
            displayName = displayName,
            mimeType = context.contentResolver.getType(uri),
            sizeBytes = sizeBytes,
            observedAt = observedAt,
        )
    }

    private fun isSupported(uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)?.lowercase()
        val path = uri.lastPathSegment?.lowercase().orEmpty()
        return mimeType in SUPPORTED_MIME_TYPES ||
            path.endsWith(".txt") ||
            path.endsWith(".md") ||
            path.endsWith(".markdown")
    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    private fun selectedUriStrings(): Set<String> {
        return prefs().getStringSet(KEY_SELECTED_URIS, emptySet()).orEmpty().toSet()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun missingSources(availability: SourceAvailability, explanation: String): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = explanation,
                connectorId = metadata.connectorId,
            )
        }
    }

    private fun Reader.readTextLimited(maxChars: Int): String {
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val builder = StringBuilder()
        while (builder.length < maxChars) {
            val remaining = maxChars - builder.length
            val readCount = read(buffer, 0, minOf(buffer.size, remaining))
            if (readCount <= 0) break
            builder.append(buffer, 0, readCount)
        }
        return builder.toString()
    }

    companion object {
        val CONNECTOR_METADATA = ConnectorMetadata(
            connectorId = LocalFileMemoryExtractor.CONNECTOR_ID,
            displayName = "Local Files",
            sourceKinds = setOf(SourceKind.LOCAL_FILE, SourceKind.MARKDOWN_NOTE),
            connectorCapabilities = setOf(ConnectorCapability.LOCAL_FILES),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT),
            defaultEnabled = false,
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_local_files"
        private const val KEY_SELECTED_URIS = "selected_uris"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val MAX_TRANSIENT_CHARS = 64 * 1024
        private val SUPPORTED_MIME_TYPES = setOf("text/plain", "text/markdown", "text/x-markdown")
    }
}

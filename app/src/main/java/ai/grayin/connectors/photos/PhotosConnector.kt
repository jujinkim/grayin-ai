package ai.grayin.connectors.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorPermissionAccess
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale

class PhotosConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionAccess = photoPermissionAccess()
        val permissionGranted = permissionAccess != ConnectorPermissionAccess.NONE
        val consentEnabled = isEnabled()
        val enabled = permissionGranted && consentEnabled
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
            consentEnabled = consentEnabled,
            availability = when {
                enabled -> SourceAvailability.AVAILABLE
                permissionGranted -> SourceAvailability.DISABLED
                else -> SourceAvailability.MISSING_PERMISSION
            },
            permissionGranted = permissionGranted,
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = when {
                !enabled -> ProcessingState.SKIPPED
                lastIndexedAt != null -> ProcessingState.COMPLETED
                else -> ProcessingState.STALE
            },
            lastIndexedAt = lastIndexedAt,
        )
    }

    override suspend fun permissionState(): ConnectorPermissionState {
        val permissionAccess = photoPermissionAccess()
        val permissionGranted = permissionAccess != ConnectorPermissionAccess.NONE
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = PhotoPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT),
            explanation = when (permissionAccess) {
                ConnectorPermissionAccess.FULL ->
                    "Full photo-library permission is available. Invoke this source before indexing."

                ConnectorPermissionAccess.PARTIAL ->
                    "Selected-photos access is available. Reopen the permission dialog to change the selection."

                ConnectorPermissionAccess.NONE ->
                    "Grant full or selected-photos read access to invoke photo metadata for indexing."
            },
            access = permissionAccess,
        )
    }

    override suspend fun invoke(): ConnectorPermissionState {
        val permissionState = permissionState()
        if (permissionState.permissionGranted) {
            check(prefs().edit().putBoolean(KEY_ENABLED, true).commit()) {
                "Could not persist photos connector consent."
            }
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (photoPermissionAccess() == ConnectorPermissionAccess.NONE) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED)
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, ConnectorScanIssueCode.SOURCE_NOT_INVOKED)
        }

        val from = scope.from ?: now.minus(DEFAULT_PAST_WINDOW)
        val until = scope.until ?: now
        val readResult = readPhotoRows(from, until, now)
        val rows = readResult.rows
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = if (rows.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = rows.map { it.sourceReference },
            derivedEvents = rows.map { it.derivedEvent },
            citations = rows.map { it.citation },
            replaceExistingConnectorData = PhotoSnapshotPolicy.shouldReplace(readResult.queryCompleted),
            missingSources = buildList {
                if (rows.isEmpty()) {
                    addAll(
                        missingSources(
                            SourceAvailability.NOT_INDEXED,
                            ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE,
                        ),
                    )
                }
                if (readResult.outputLimited) {
                    addAll(
                        missingSources(
                            SourceAvailability.STALE,
                            ConnectorScanIssueCode.PHOTO_METADATA_LIMIT_REACHED,
                        ),
                    )
                }
            },
            scopeFrom = from,
            scopeUntil = until,
            scannedAt = now,
        )
    }

    override suspend fun onScanStored(scanResult: ConnectorScanResult) {
        if (scanResult.processingState == ProcessingState.COMPLETED) {
            prefs().edit().putString(KEY_LAST_INDEXED_AT, scanResult.scannedAt.toString()).apply()
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        check(prefs().edit().clear().commit()) { "Could not clear photos connector consent." }
        return ConnectorRevokeResult(
            connectorId = CONNECTOR_ID,
            revokedAt = Instant.now(),
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = CONNECTOR_ID),
        )
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }

    private fun readPhotoRows(
        from: Instant,
        until: Instant,
        observedAt: Instant,
    ): PhotoReadResult {
        val rangeQuery = PhotoRangeSelectionPolicy.query(from, until)
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            rangeQuery.selection,
            rangeQuery.selectionArgs,
            rangeQuery.sortOrder,
        )?.use { cursor ->
            val rows = mutableListOf<PhotoExtractionResult>()
            var outputLimited = false
            while (cursor.moveToNext()) {
                if (rows.size >= MAX_PHOTOS_PER_SCAN) {
                    outputLimited = true
                    break
                }
                rows += cursor.toExtractionResult(observedAt)
            }
            PhotoReadResult(rows, outputLimited, queryCompleted = true)
        } ?: PhotoReadResult(emptyList(), outputLimited = false, queryCompleted = false)
    }

    private fun android.database.Cursor.toExtractionResult(observedAt: Instant): PhotoExtractionResult {
        val photoId = getLong(0)
        val takenAtMillis = if (isNull(1) || getLong(1) <= 0L) null else getLong(1)
        val modifiedSeconds = if (isNull(2) || getLong(2) <= 0L) null else getLong(2)
        val metadata = PhotoMetadataPolicy.close(
            mimeType = if (isNull(3)) null else getString(3),
            width = if (isNull(4)) null else getInt(4),
            height = if (isNull(5)) null else getInt(5),
        )
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val sourceHash = sha256(contentUri.toString())
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val occurredAt = takenAtMillis?.let(Instant::ofEpochMilli)
            ?: modifiedSeconds?.let { Instant.ofEpochSecond(it) }
            ?: observedAt
        val derivedValues = PhotoDerivedValuePolicy.create(occurredAt, metadata)
        return PhotoExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.PHOTO,
                localPointer = contentUri.toString(),
                externalIdHash = sourceHash,
                observedAt = observedAt,
                modifiedAt = occurredAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.PHOTO_INDEX,
                sourceReferenceIds = listOf(sourceId),
                summary = derivedValues.summary,
                startedAt = occurredAt,
                keywords = derivedValues.keywords,
                labels = derivedValues.labels,
                confidence = ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = derivedValues.citationLabel,
                observedAt = observedAt,
                confidence = ConfidenceLevel.MEDIUM,
            ),
        )
    }

    private fun skipped(
        scannedAt: Instant,
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.SKIPPED,
            missingSources = missingSources(availability, issueCode),
            scannedAt = scannedAt,
        )
    }

    private fun missingSources(
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = issueCode.defaultEnglish,
                connectorId = CONNECTOR_ID,
                issueCode = issueCode,
            )
        }
    }

    private fun photoPermissionAccess(): ConnectorPermissionAccess {
        return PhotoPermissionPolicy.access(
            sdkInt = Build.VERSION.SDK_INT,
            fullAccessGranted = context.checkSelfPermission(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                },
            ) == PackageManager.PERMISSION_GRANTED,
            selectedPhotosGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private data class PhotoExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
    )

    private data class PhotoReadResult(
        val rows: List<PhotoExtractionResult>,
        val outputLimited: Boolean,
        val queryCompleted: Boolean,
    )

    companion object {
        const val CONNECTOR_ID = "photos"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Photos",
            sourceKinds = setOf(SourceKind.PHOTO),
            connectorCapabilities = setOf(ConnectorCapability.PHOTOS),
            memoryCapabilities = setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_MEDIA,
            ),
            indexingMode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            defaultEnabled = false,
            supportsDateRangeIndexing = true,
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_photos"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val MAX_PHOTOS_PER_SCAN = 200
        private const val HASH_ID_CHARS = 32
        private val DEFAULT_PAST_WINDOW = Duration.ofDays(90)
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
    }
}

internal object PhotoSnapshotPolicy {
    /** A completed MediaStore query is the complete persisted snapshot for that requested range. */
    fun shouldReplace(queryCompleted: Boolean): Boolean = queryCompleted
}

internal object PhotoPermissionPolicy {
    fun access(
        sdkInt: Int,
        fullAccessGranted: Boolean,
        selectedPhotosGranted: Boolean,
    ): ConnectorPermissionAccess {
        return when {
            fullAccessGranted -> ConnectorPermissionAccess.FULL
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && selectedPhotosGranted ->
                ConnectorPermissionAccess.PARTIAL

            else -> ConnectorPermissionAccess.NONE
        }
    }

    fun requiredPermissions(sdkInt: Int): List<String> {
        return when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun canReselect(access: ConnectorPermissionAccess): Boolean {
        return access == ConnectorPermissionAccess.PARTIAL
    }
}

internal data class PhotoRangeQuery(
    val selection: String,
    val selectionArgs: Array<String>,
    val sortOrder: String,
)

internal object PhotoRangeSelectionPolicy {
    fun query(from: Instant, until: Instant): PhotoRangeQuery {
        require(from.isBefore(until)) { "Photo range must be half-open and non-empty." }
        val dateTaken = MediaStore.Images.Media.DATE_TAKEN
        val dateModified = MediaStore.Images.Media.DATE_MODIFIED
        val effectiveTimestamp = "CASE WHEN $dateTaken IS NOT NULL AND $dateTaken > 0 " +
            "THEN $dateTaken ELSE $dateModified * 1000 END"
        return PhotoRangeQuery(
            selection = "($dateTaken > 0 OR $dateModified > 0) AND " +
                "$effectiveTimestamp >= ? AND $effectiveTimestamp < ?",
            selectionArgs = arrayOf(from.toEpochMilli().toString(), until.toEpochMilli().toString()),
            sortOrder = "$effectiveTimestamp DESC",
        )
    }
}

internal data class ClosedPhotoMetadata(
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
)

internal object PhotoMetadataPolicy {
    fun close(mimeType: String?, width: Int?, height: Int?): ClosedPhotoMetadata {
        return ClosedPhotoMetadata(
            mimeType = mimeType?.trim()?.lowercase(Locale.ROOT)?.takeIf(ALLOWED_MIME_TYPES::contains),
            width = width?.takeIf { value -> value in 1..MAX_DIMENSION_PIXELS },
            height = height?.takeIf { value -> value in 1..MAX_DIMENSION_PIXELS },
        )
    }

    private const val MAX_DIMENSION_PIXELS = 100_000
    private val ALLOWED_MIME_TYPES = setOf(
        "image/avif",
        "image/gif",
        "image/heic",
        "image/heif",
        "image/jpeg",
        "image/png",
        "image/webp",
    )
}

internal data class ClosedPhotoDerivedValues(
    val summary: String,
    val keywords: List<String>,
    val labels: List<String>,
    val citationLabel: String,
)

internal object PhotoDerivedValuePolicy {
    fun create(occurredAt: Instant, metadata: ClosedPhotoMetadata): ClosedPhotoDerivedValues {
        val subtype = metadata.mimeType?.substringAfter('/')
        val orientation = if (metadata.width != null && metadata.height != null) {
            if (metadata.width >= metadata.height) "landscape" else "portrait"
        } else {
            null
        }
        val dimensions = if (metadata.width != null && metadata.height != null) {
            " Dimensions: ${metadata.width}x${metadata.height}."
        } else {
            ""
        }
        val mime = metadata.mimeType?.let { " Type: $it." }.orEmpty()
        return ClosedPhotoDerivedValues(
            summary = "Photo metadata indexed at $occurredAt.$mime$dimensions",
            keywords = listOfNotNull("photo", subtype, orientation),
            labels = listOfNotNull("photo", subtype, orientation),
            citationLabel = "Photo metadata",
        )
    }
}

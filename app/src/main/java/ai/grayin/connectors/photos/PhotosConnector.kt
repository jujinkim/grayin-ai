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

class PhotosConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasPhotosPermission()
        val enabled = permissionGranted && isEnabled()
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
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
        val permissionGranted = hasPhotosPermission()
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = listOf(requiredPermission()),
            explanation = if (permissionGranted) {
                "Photo permission is available. Invoke this source before indexing."
            } else {
                "Grant photo read permission to invoke photo metadata for indexing."
            },
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
        if (!hasPhotosPermission()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED)
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, ConnectorScanIssueCode.SOURCE_NOT_INVOKED)
        }

        val from = scope.from ?: now.minus(DEFAULT_PAST_WINDOW)
        val until = scope.until ?: now
        val rows = readPhotoRows(from, until, now)
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = if (rows.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = rows.map { it.sourceReference },
            derivedEvents = rows.map { it.derivedEvent },
            citations = rows.map { it.citation },
            missingSources = if (rows.isEmpty()) {
                missingSources(SourceAvailability.NOT_INDEXED, ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE)
            } else {
                emptyList()
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
    ): List<PhotoExtractionResult> {
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
        val selectionArgs = arrayOf(from.toEpochMilli().toString(), until.toEpochMilli().toString())
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_PHOTOS_PER_SCAN) {
                    add(cursor.toExtractionResult(observedAt))
                }
            }
        }.orEmpty()
    }

    private fun android.database.Cursor.toExtractionResult(observedAt: Instant): PhotoExtractionResult {
        val photoId = getLong(0)
        val takenAtMillis = if (isNull(1) || getLong(1) <= 0L) null else getLong(1)
        val modifiedSeconds = if (isNull(2) || getLong(2) <= 0L) null else getLong(2)
        val displayName = if (isNull(3)) null else getString(3)
        val mimeType = if (isNull(4)) null else getString(4)
        val width = if (isNull(5)) null else getInt(5)
        val height = if (isNull(6)) null else getInt(6)
        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val sourceHash = sha256(contentUri.toString())
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val occurredAt = takenAtMillis?.let(Instant::ofEpochMilli)
            ?: modifiedSeconds?.let { Instant.ofEpochSecond(it) }
            ?: observedAt
        val labels = photoLabels(mimeType, width, height)
        val keywords = keywords(listOfNotNull(displayName, mimeType, width?.toString(), height?.toString()).joinToString(" "))
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
                summary = photoSummary(displayName, occurredAt, mimeType, width, height),
                startedAt = occurredAt,
                keywords = keywords,
                labels = labels,
                confidence = ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "Photo metadata: ${displayName?.takeIf { it.isNotBlank() } ?: photoId}",
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

    private fun hasPhotosPermission(): Boolean {
        return context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun photoSummary(
        displayName: String?,
        occurredAt: Instant,
        mimeType: String?,
        width: Int?,
        height: Int?,
    ): String {
        val name = displayName?.takeIf { it.isNotBlank() } ?: "photo"
        val dimensions = if (width != null && height != null) " Dimensions: ${width}x$height." else ""
        val mime = mimeType?.let { " Type: $it." }.orEmpty()
        return "Photo metadata indexed: $name at $occurredAt.$mime$dimensions"
    }

    private fun photoLabels(mimeType: String?, width: Int?, height: Int?): List<String> {
        return buildList {
            add("photo")
            mimeType?.let(::add)
            if (width != null && height != null) {
                add(if (width >= height) "landscape" else "portrait")
            }
        }
    }

    private fun keywords(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH && it !in STOP_WORDS }
            .distinct()
            .take(MAX_KEYWORDS)
            .toList()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private data class PhotoExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
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
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_photos"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val MAX_PHOTOS_PER_SCAN = 200
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MAX_KEYWORDS = 16
        private const val HASH_ID_CHARS = 32
        private val DEFAULT_PAST_WINDOW = Duration.ofDays(90)
        private val WORD_PATTERN = Regex("[\\p{L}\\p{Nd}]+")
        private val STOP_WORDS = setOf("jpg", "jpeg", "png", "webp", "image")
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
    }
}

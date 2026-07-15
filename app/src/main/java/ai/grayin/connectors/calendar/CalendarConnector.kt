package ai.grayin.connectors.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorCapability
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

class CalendarConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasCalendarPermission()
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
        val permissionGranted = hasCalendarPermission()
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = listOf(Manifest.permission.READ_CALENDAR),
            explanation = if (permissionGranted) {
                "Calendar permission is available. Invoke this source before indexing."
            } else {
                "Grant calendar read permission to invoke calendar events for indexing."
            },
        )
    }

    override suspend fun invoke(): ConnectorPermissionState {
        val permissionState = permissionState()
        if (permissionState.permissionGranted) {
            prefs().edit().putBoolean(KEY_ENABLED, true).apply()
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (!hasCalendarPermission()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, "Calendar permission was not granted.")
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, "Calendar source has not been invoked.")
        }

        val from = scope.from ?: now.minus(DEFAULT_PAST_WINDOW)
        val until = scope.until ?: now.plus(DEFAULT_FUTURE_WINDOW)
        val rows = readCalendarRows(from, until, now)
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = if (rows.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = rows.map { it.sourceReference },
            derivedEvents = rows.map { it.derivedEvent },
            citations = rows.map { it.citation },
            missingSources = if (rows.isEmpty()) {
                missingSources(SourceAvailability.NOT_INDEXED, "No calendar events found in the indexed time window.")
            } else {
                emptyList()
            },
            scannedAt = now,
        )
    }

    override suspend fun onScanStored(scanResult: ConnectorScanResult) {
        if (scanResult.processingState == ProcessingState.COMPLETED) {
            prefs().edit().putString(KEY_LAST_INDEXED_AT, scanResult.scannedAt.toString()).apply()
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        prefs().edit().clear().apply()
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

    private fun readCalendarRows(
        from: Instant,
        until: Instant,
        observedAt: Instant,
    ): List<CalendarExtractionResult> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toEpochMilli().toString())
            .appendPath(until.toEpochMilli().toString())
            .build()
        return context.contentResolver.query(
            uri,
            PROJECTION,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_EVENTS_PER_SCAN) {
                    add(cursor.toExtractionResult(observedAt))
                }
            }
        }.orEmpty()
    }

    private fun android.database.Cursor.toExtractionResult(observedAt: Instant): CalendarExtractionResult {
        val eventId = getLong(0)
        val beginMillis = getLong(1)
        val endMillis = if (isNull(2)) null else getLong(2)
        val title = if (isNull(3)) null else getString(3)
        val location = if (isNull(4)) null else getString(4)
        val calendarName = if (isNull(5)) null else getString(5)
        val allDay = !isNull(6) && getInt(6) == 1
        val sourceHash = sha256("$eventId:$beginMillis")
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventMemoryId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val startedAt = Instant.ofEpochMilli(beginMillis)
        val endedAt = endMillis?.let(Instant::ofEpochMilli)
        val label = title?.takeIf { it.isNotBlank() } ?: "Untitled calendar event"
        val keywords = keywords(listOf(label, location, calendarName).filterNotNull().joinToString(" "))
        return CalendarExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.CALENDAR,
                localPointer = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString()).toString(),
                externalIdHash = sourceHash,
                sourceAppIdentifier = calendarName,
                observedAt = observedAt,
                modifiedAt = startedAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventMemoryId,
                kind = DerivedMemoryEventKind.CALENDAR_EVENT,
                sourceReferenceIds = listOf(sourceId),
                summary = calendarSummary(label, startedAt, endedAt, location, allDay),
                startedAt = startedAt,
                endedAt = endedAt,
                keywords = keywords,
                labels = buildList {
                    add("calendar")
                    if (allDay) add("all-day")
                    if (!location.isNullOrBlank()) add("location")
                },
                confidence = if (title.isNullOrBlank()) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventMemoryId,
                label = "Calendar: $label",
                observedAt = observedAt,
                confidence = if (title.isNullOrBlank()) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
            ),
        )
    }

    private fun skipped(
        scannedAt: Instant,
        availability: SourceAvailability,
        explanation: String,
    ): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.SKIPPED,
            missingSources = missingSources(availability, explanation),
            scannedAt = scannedAt,
        )
    }

    private fun missingSources(availability: SourceAvailability, explanation: String): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = explanation,
                connectorId = CONNECTOR_ID,
            )
        }
    }

    private fun hasCalendarPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun calendarSummary(
        label: String,
        startedAt: Instant,
        endedAt: Instant?,
        location: String?,
        allDay: Boolean,
    ): String {
        val timing = if (allDay) {
            "all-day on $startedAt"
        } else {
            "from $startedAt" + (endedAt?.let { " to $it" } ?: "")
        }
        val locationSuffix = location?.takeIf { it.isNotBlank() }?.let { " Location signal: $it." }.orEmpty()
        return "Calendar event indexed: $label, $timing.$locationSuffix"
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

    private data class CalendarExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
    )

    companion object {
        const val CONNECTOR_ID = "calendar"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Calendar",
            sourceKinds = setOf(SourceKind.CALENDAR),
            connectorCapabilities = setOf(ConnectorCapability.CALENDAR),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
            defaultEnabled = false,
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_calendar"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val MAX_EVENTS_PER_SCAN = 200
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MAX_KEYWORDS = 16
        private const val HASH_ID_CHARS = 32
        private val DEFAULT_PAST_WINDOW = Duration.ofDays(90)
        private val DEFAULT_FUTURE_WINDOW = Duration.ofDays(30)
        private val WORD_PATTERN = Regex("[\\p{L}\\p{Nd}]+")
        private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "this", "that")
        private val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.ALL_DAY,
        )
    }
}

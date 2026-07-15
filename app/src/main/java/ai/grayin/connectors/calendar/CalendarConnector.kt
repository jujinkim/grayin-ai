package ai.grayin.connectors.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import ai.grayin.connectors.ConnectorValuePolicy
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

class CalendarConnector(
    private val context: Context,
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasCalendarPermission()
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
            check(prefs().edit().putBoolean(KEY_ENABLED, true).commit()) {
                "Could not persist calendar connector consent."
            }
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (!hasCalendarPermission()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED)
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, ConnectorScanIssueCode.SOURCE_NOT_INVOKED)
        }

        val from = scope.from ?: now.minus(DEFAULT_PAST_WINDOW)
        val until = scope.until ?: now.plus(DEFAULT_FUTURE_WINDOW)
        val readResult = readCalendarRows(from, until, now)
        val rows = readResult.rows
        val emptyReadIssue = CalendarScanPolicy.emptyReadIssue(readResult.queryCompleted)
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = if (rows.isEmpty()) ProcessingState.SKIPPED else ProcessingState.COMPLETED,
            sourceReferences = rows.map { it.sourceReference },
            derivedEvents = rows.map { it.derivedEvent },
            citations = rows.map { it.citation },
            replaceExistingConnectorData = CalendarScanPolicy.shouldReplace(),
            missingSources = buildList {
                if (rows.isEmpty()) {
                    addAll(
                        missingSources(
                            emptyReadIssue.availability,
                            emptyReadIssue.issueCode,
                        ),
                    )
                }
                if (readResult.outputLimited) {
                    addAll(
                        missingSources(
                            SourceAvailability.STALE,
                            ConnectorScanIssueCode.CALENDAR_EVENT_LIMIT_REACHED,
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
        check(prefs().edit().clear().commit()) { "Could not clear calendar connector consent." }
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
    ): CalendarReadResult {
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
            val rows = mutableListOf<CalendarExtractionResult>()
            var outputLimited = false
            while (cursor.moveToNext()) {
                    val beginMillis = cursor.getLong(1)
                    val endMillis = if (cursor.isNull(2)) null else cursor.getLong(2)
                    if (
                        CalendarRangePolicy.overlaps(
                            eventStartMillis = beginMillis,
                            eventEndMillis = endMillis,
                            fromMillis = from.toEpochMilli(),
                            untilExclusiveMillis = until.toEpochMilli(),
                        )
                    ) {
                        if (rows.size >= MAX_EVENTS_PER_SCAN) {
                            outputLimited = true
                            break
                        }
                        rows += cursor.toExtractionResult(observedAt)
                    }
            }
            CalendarReadResult(rows, outputLimited, queryCompleted = true)
        } ?: CalendarReadResult(emptyList(), outputLimited = false, queryCompleted = false)
    }

    private fun android.database.Cursor.toExtractionResult(observedAt: Instant): CalendarExtractionResult {
        val eventId = getLong(0)
        val beginMillis = getLong(1)
        val endMillis = if (isNull(2)) null else getLong(2)
        val fields = CalendarValuePolicy.close(
            title = if (isNull(3)) null else getString(3),
            location = if (isNull(4)) null else getString(4),
        )
        val allDay = !isNull(5) && getInt(5) == 1
        val sourceHash = sha256("$eventId:$beginMillis")
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventMemoryId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val startedAt = Instant.ofEpochMilli(beginMillis)
        val endedAt = endMillis?.let(Instant::ofEpochMilli)
        val label = fields.title ?: "Untitled calendar event"
        val keywords = keywords(listOfNotNull(label, fields.location).joinToString(" "))
        return CalendarExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.CALENDAR,
                localPointer = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString()).toString(),
                externalIdHash = sourceHash,
                sourceAppIdentifier = CalendarValuePolicy.SOURCE_APP_IDENTIFIER,
                observedAt = observedAt,
                modifiedAt = startedAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventMemoryId,
                kind = DerivedMemoryEventKind.CALENDAR_EVENT,
                sourceReferenceIds = listOf(sourceId),
                summary = calendarSummary(label, startedAt, endedAt, fields.location, allDay),
                startedAt = startedAt,
                endedAt = endedAt,
                keywords = keywords,
                labels = buildList {
                    add("calendar")
                    if (allDay) add("all-day")
                    if (fields.location != null) add("location")
                },
                confidence = if (fields.title == null) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
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
                confidence = if (fields.title == null) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
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

    private data class CalendarReadResult(
        val rows: List<CalendarExtractionResult>,
        val outputLimited: Boolean,
        val queryCompleted: Boolean,
    )

    companion object {
        const val CONNECTOR_ID = "calendar"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Calendar",
            sourceKinds = setOf(SourceKind.CALENDAR),
            connectorCapabilities = setOf(ConnectorCapability.CALENDAR),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
            indexingMode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            defaultEnabled = false,
            supportsDateRangeIndexing = true,
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
            CalendarContract.Instances.ALL_DAY,
        )
    }
}

internal data class CalendarEmptyReadIssue(
    val availability: SourceAvailability,
    val issueCode: ConnectorScanIssueCode,
)

internal object CalendarScanPolicy {
    fun emptyReadIssue(queryCompleted: Boolean): CalendarEmptyReadIssue {
        return if (queryCompleted) {
            CalendarEmptyReadIssue(
                availability = SourceAvailability.NOT_INDEXED,
                issueCode = ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE,
            )
        } else {
            CalendarEmptyReadIssue(
                availability = SourceAvailability.STALE,
                issueCode = ConnectorScanIssueCode.SOURCE_UNAVAILABLE,
            )
        }
    }

    /** Calendar scans remain incremental and never replace their prior connector graph. */
    fun shouldReplace(): Boolean = false
}

internal data class ClosedCalendarFields(
    val title: String?,
    val location: String?,
)

internal object CalendarValuePolicy {
    const val SOURCE_APP_IDENTIFIER = "android-calendar"

    fun close(title: String?, location: String?): ClosedCalendarFields {
        return ClosedCalendarFields(
            title = ConnectorValuePolicy.closedText(title, MAX_TITLE_BYTES),
            location = ConnectorValuePolicy.closedText(location, MAX_LOCATION_BYTES),
        )
    }

    private const val MAX_TITLE_BYTES = 384
    private const val MAX_LOCATION_BYTES = 384
}

internal object CalendarRangePolicy {
    fun overlaps(
        eventStartMillis: Long,
        eventEndMillis: Long?,
        fromMillis: Long,
        untilExclusiveMillis: Long,
    ): Boolean {
        require(fromMillis < untilExclusiveMillis) { "Calendar range must be half-open and non-empty." }
        val minimumEnd = if (eventStartMillis == Long.MAX_VALUE) Long.MAX_VALUE else eventStartMillis + 1L
        val effectiveEnd = eventEndMillis?.coerceAtLeast(minimumEnd) ?: minimumEnd
        return eventStartMillis < untilExclusiveMillis && effectiveEnd > fromMillis
    }
}

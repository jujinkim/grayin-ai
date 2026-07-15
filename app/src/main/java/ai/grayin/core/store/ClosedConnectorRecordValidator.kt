package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.time.Duration
import kotlin.math.abs
import kotlin.math.round
import kotlin.text.Charsets.UTF_8

/**
 * Validates the closed, schema-v8 derived records shared by live scans and encrypted imports.
 *
 * This deliberately depends only on core models. Android connector implementations remain free
 * to evolve their platform reads, but any persisted output must continue to match this boundary.
 */
internal object ClosedConnectorRecordValidator {
    private val connectorIds = setOf(LOCATION, PHOTOS, CALENDAR, NOTIFICATION, APP_USAGE)

    fun validate(scanResult: ConnectorScanResult) {
        validate(scanResult, storedSnapshot = false)
    }

    fun validateStoredSnapshot(scanResult: ConnectorScanResult) {
        validate(scanResult, storedSnapshot = true)
    }

    private fun validate(scanResult: ConnectorScanResult, storedSnapshot: Boolean) {
        validate(
            connectorId = scanResult.connectorId,
            sources = scanResult.sourceReferences,
            events = scanResult.derivedEvents,
            citations = scanResult.citations,
            placeClusters = scanResult.placeClusters,
            appUsageSummaries = scanResult.appUsageSummaries,
            storedSnapshot = storedSnapshot,
        )
    }

    fun validate(snapshot: LocalMemorySnapshot) {
        connectorIds.forEach { connectorId ->
            validate(
                connectorId = connectorId,
                sources = snapshot.sourceReferences.filter { source -> source.connectorId == connectorId },
                events = snapshot.derivedMemoryEvents.filter { event ->
                    event.id.startsWith("event:$connectorId:")
                },
                citations = snapshot.citations.filter { citation ->
                    citation.id.startsWith("citation:$connectorId:")
                },
                placeClusters = snapshot.placeClusters.filter { cluster ->
                    cluster.id.startsWith("place-cluster:$connectorId:")
                },
                appUsageSummaries = snapshot.appUsageSummaries.filter { summary ->
                    summary.id.startsWith("app-usage:$connectorId:")
                },
                storedSnapshot = true,
            )
        }
    }

    private fun validate(
        connectorId: String,
        sources: List<SourceReference>,
        events: List<DerivedMemoryEvent>,
        citations: List<MemoryCitation>,
        placeClusters: List<PlaceCluster>,
        appUsageSummaries: List<AppUsageSummary>,
        storedSnapshot: Boolean,
    ) {
        if (connectorId !in connectorIds) return
        require(sources.size == events.size && events.size == citations.size) {
            "A closed $connectorId graph must contain one event and citation per source."
        }
        val eventsById = events.associateBy(DerivedMemoryEvent::id)
        val citationsById = citations.associateBy(MemoryCitation::id)
        sources.forEach { source ->
            val hash = requireNotNull(source.externalIdHash)
            require(HASH.matches(hash) && source.hmacHash == null)
            require(source.id == "source:$connectorId:$hash")
            val event = requireNotNull(eventsById["event:$connectorId:$hash"])
            val citation = requireNotNull(citationsById["citation:$connectorId:$hash"])
            require(event.sourceReferenceIds == listOf(source.id))
            require(event.citationIds == listOf(citation.id))
            require(citation.sourceReferenceId == source.id && citation.derivedMemoryEventId == event.id)
            require(source.observedAt != null && source.modifiedAt != null)
            require(event.createdAt == source.observedAt && citation.observedAt == source.observedAt)
            require(citation.confidence == event.confidence)
            when (connectorId) {
                LOCATION -> validateLocation(source, event, citation)
                PHOTOS -> validatePhoto(source, event, citation)
                CALENDAR -> validateCalendar(source, event, citation)
                NOTIFICATION -> validateNotification(source, event, citation)
                APP_USAGE -> validateAppUsage(source, event, citation)
            }
        }

        when (connectorId) {
            LOCATION -> {
                require(appUsageSummaries.isEmpty())
                val clusteredSourceIds = placeClusters.flatMap(PlaceCluster::sourceReferenceIds)
                require(clusteredSourceIds.distinct().size == clusteredSourceIds.size)
                require(clusteredSourceIds.toSet() == sources.map(SourceReference::id).toSet())
                placeClusters.forEach { cluster ->
                    validateLocationCluster(cluster, sources, events, storedSnapshot)
                }
            }

            APP_USAGE -> {
                require(placeClusters.isEmpty())
                require(appUsageSummaries.isEmpty()) {
                    "Schema v8 has no canonical AppUsageSummary producer."
                }
            }

            else -> require(placeClusters.isEmpty() && appUsageSummaries.isEmpty())
        }
    }

    private fun validateLocation(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        require(source.sourceKind == SourceKind.LOCATION)
        require(source.sourceAppIdentifier == null && source.sensitivity == SensitivityLevel.HIGH)
        require(event.kind == DerivedMemoryEventKind.PLACE_VISIT)
        require(event.startedAt == source.modifiedAt && event.endedAt == null)
        require(event.sensitivity == SensitivityLevel.HIGH)
        require(event.confidence in setOf(ConfidenceLevel.LOW, ConfidenceLevel.MEDIUM))
        require(event.entities.isEmpty())
        require(event.keywords.size >= 3 && event.labels.size >= 3)
        require(event.keywords.take(2) == listOf("location", "place"))
        require(event.labels.take(2) == listOf("location", "place-visit"))
        val provider = event.keywords[2]
        require(provider == event.labels[2] && provider in LOCATION_PROVIDERS)
        source.localPointer?.let { pointer -> require(pointer == "location-provider:$provider") }
        event.keywords.drop(3).forEach { value -> requireClosedText(value, MAX_LOCATION_KEYWORD_BYTES) }
        event.labels.drop(3).forEach { value -> requireClosedText(value, MAX_LOCATION_KEYWORD_BYTES) }

        val region = citation.label.removePrefix(LOCATION_CITATION_PREFIX)
        require(citation.label.startsWith(LOCATION_CITATION_PREFIX))
        requireClosedText(region, MAX_LOCATION_REGION_BYTES)
        val base = "Location sample indexed near $region at ${event.startedAt}."
        require(event.summary == base || (event.summary.startsWith(base) && WEATHER_SUFFIX.matches(event.summary.removePrefix(base))))
    }

    private fun validateLocationCluster(
        cluster: PlaceCluster,
        sources: List<SourceReference>,
        events: List<DerivedMemoryEvent>,
        storedSnapshot: Boolean,
    ) {
        require(LOCATION_CLUSTER_ID.matches(cluster.id))
        require(cluster.label == null)
        cluster.regionLabel?.let { value -> requireClosedText(value, MAX_LOCATION_REGION_BYTES) }
        require(cluster.centroidLatitude != null && cluster.centroidLongitude != null)
        require(isCoordinateGridValue(cluster.centroidLatitude) && isCoordinateGridValue(cluster.centroidLongitude))
        require(cluster.radiusMeters == null || cluster.radiusMeters in 0.0..MAX_LOCATION_RADIUS_METERS)
        require(cluster.sourceReferenceIds.isNotEmpty())
        require(cluster.sourceReferenceIds.distinct().size == cluster.sourceReferenceIds.size)
        if (!storedSnapshot) require(cluster.sourceReferenceIds.size == 1)
        val clusterSources = cluster.sourceReferenceIds.map { sourceId ->
            requireNotNull(sources.find { source -> source.id == sourceId })
        }
        val sourceTimes = clusterSources.map { source -> requireNotNull(source.modifiedAt) }
        val clusterEvents = clusterSources.map { source ->
            requireNotNull(events.find { event -> event.sourceReferenceIds == listOf(source.id) })
        }
        require(cluster.firstSeenAt == sourceTimes.minOrNull() && cluster.lastSeenAt == sourceTimes.maxOrNull())
        require(cluster.visitCount == cluster.sourceReferenceIds.size)
        require(cluster.confidence == clusterEvents.maxOf(DerivedMemoryEvent::confidence))
    }

    private fun validatePhoto(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        require(source.sourceKind == SourceKind.PHOTO)
        require(source.sourceAppIdentifier == null && source.sensitivity == SensitivityLevel.HIGH)
        source.localPointer?.let { pointer -> require(pointer.startsWith("content://media/")) }
        require(event.kind == DerivedMemoryEventKind.PHOTO_INDEX)
        require(event.startedAt == source.modifiedAt && event.endedAt == null)
        require(event.sensitivity == SensitivityLevel.HIGH && event.confidence == ConfidenceLevel.MEDIUM)
        require(event.entities.isEmpty() && citation.label == "Photo metadata")
        require(event.labels == event.keywords)
        require(event.labels.isNotEmpty() && event.labels.first() == "photo" && event.labels.size <= 3)
        event.labels.drop(1).forEach { value -> require(value in PHOTO_LABELS) }
        val summary = PHOTO_SUMMARY.matchEntire(event.summary)
        require(summary != null && summary.groupValues[1] == event.startedAt.toString())
        val mimeSubtype = summary.groupValues[2]
        if (mimeSubtype.isNotEmpty()) require(mimeSubtype in PHOTO_MIME_SUBTYPES && mimeSubtype in event.labels)
        val width = summary.groupValues[3].toIntOrNull()
        val height = summary.groupValues[4].toIntOrNull()
        require((width == null) == (height == null))
        val expectedLabels = buildList {
            add("photo")
            if (mimeSubtype.isNotEmpty()) add(mimeSubtype)
            if (width != null && height != null) add(if (width >= height) "landscape" else "portrait")
        }
        require(event.labels == expectedLabels)
        if (width != null && height != null) {
            require(width in 1..MAX_PHOTO_DIMENSION && height in 1..MAX_PHOTO_DIMENSION)
        }
    }

    private fun validateCalendar(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        require(source.sourceKind == SourceKind.CALENDAR)
        require(source.sourceAppIdentifier == CALENDAR_SOURCE_APP)
        require(source.sensitivity == SensitivityLevel.HIGH)
        source.localPointer?.let { pointer -> require(pointer.startsWith("content://com.android.calendar/events/")) }
        require(event.kind == DerivedMemoryEventKind.CALENDAR_EVENT)
        require(event.startedAt == source.modifiedAt)
        require(event.sensitivity == SensitivityLevel.HIGH)
        require(event.entities.isEmpty())
        require(event.labels.isNotEmpty() && event.labels.first() == "calendar")
        require(event.labels.drop(1).all { label -> label == "all-day" || label == "location" })
        require(event.labels.distinct().size == event.labels.size)
        require(
            event.labels == buildList {
                add("calendar")
                if ("all-day" in event.labels) add("all-day")
                if ("location" in event.labels) add("location")
            },
        )
        event.keywords.forEach { keyword -> require(CALENDAR_KEYWORD.matches(keyword)) }

        require(citation.label.startsWith(CALENDAR_CITATION_PREFIX))
        val title = citation.label.removePrefix(CALENDAR_CITATION_PREFIX)
        requireClosedText(title, MAX_CALENDAR_FIELD_BYTES)
        val allowedConfidence = if (title == "Untitled calendar event") {
            setOf(ConfidenceLevel.MEDIUM, ConfidenceLevel.HIGH)
        } else {
            setOf(ConfidenceLevel.HIGH)
        }
        require(event.confidence in allowedConfidence)
        val timing = if ("all-day" in event.labels) {
            "all-day on ${event.startedAt}"
        } else {
            "from ${event.startedAt}" + (event.endedAt?.let { endedAt -> " to $endedAt" } ?: "")
        }
        val base = "Calendar event indexed: $title, $timing."
        if ("location" in event.labels) {
            require(event.summary.startsWith("$base Location signal: ") && event.summary.endsWith('.'))
            val location = event.summary.removePrefix("$base Location signal: ").dropLast(1)
            requireClosedText(location, MAX_CALENDAR_FIELD_BYTES)
        } else {
            require(event.summary == base)
        }
    }

    private fun validateNotification(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        val packageName = requireNotNull(source.sourceAppIdentifier)
        require(
            source.sourceKind == SourceKind.NOTIFICATION &&
                PACKAGE_NAME.matches(packageName) &&
                packageName.toByteArray(UTF_8).size <= MAX_PACKAGE_NAME_BYTES,
        )
        require(source.localPointer == null && source.sensitivity == SensitivityLevel.VERY_HIGH)
        require(event.startedAt == source.modifiedAt && event.endedAt == null)
        require(event.sensitivity == SensitivityLevel.VERY_HIGH)
        require(event.entities == listOf(packageName))
        require(event.labels.size in 2..3 && event.labels.first() == "notification")
        val kindLabel = event.labels[1]
        require(kindLabel in NOTIFICATION_KIND_LABELS.getValue(event.kind))
        event.labels.getOrNull(2)?.let { category -> require(category in NOTIFICATION_CATEGORIES) }
        val expectedConfidence = if (kindLabel == "other") ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM
        require(event.confidence == expectedConfidence)
        require(event.summary == "Notification-derived $kindLabel signal from $packageName at ${event.startedAt}.")
        require(citation.label == "Notification signal: $packageName")
        event.keywords.forEach { keyword -> require(CLOSED_TOKEN.matches(keyword)) }
    }

    private fun validateAppUsage(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        val packageName = requireNotNull(source.sourceAppIdentifier)
        require(
            source.sourceKind == SourceKind.APP_USAGE &&
                PACKAGE_NAME.matches(packageName) &&
                packageName.toByteArray(UTF_8).size <= MAX_PACKAGE_NAME_BYTES,
        )
        require(source.localPointer == null && source.sensitivity == SensitivityLevel.VERY_HIGH)
        require(event.kind == DerivedMemoryEventKind.APP_USAGE)
        val startedAt = requireNotNull(event.startedAt)
        val endedAt = requireNotNull(event.endedAt)
        require(endedAt == source.modifiedAt && endedAt.isAfter(startedAt))
        require(event.sensitivity == SensitivityLevel.VERY_HIGH && event.confidence == ConfidenceLevel.MEDIUM)
        require(event.entities == listOf(packageName))
        require(citation.label.startsWith(APP_USAGE_CITATION_PREFIX))
        val alias = citation.label.removePrefix(APP_USAGE_CITATION_PREFIX)
        requireClosedText(alias, MAX_APP_ALIAS_BYTES)
        val summaryPrefix = "App usage indexed: $alias used for about "
        val summarySuffix = " minute(s) between $startedAt and $endedAt."
        require(event.summary.startsWith(summaryPrefix) && event.summary.endsWith(summarySuffix))
        val minutes = requireNotNull(
            event.summary.removePrefix(summaryPrefix).removeSuffix(summarySuffix).toLongOrNull(),
        )
        require(minutes in 1..MAX_USAGE_MINUTES)
        val exactDurationMillis = try {
            Duration.between(startedAt, endedAt).toMillis()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("App usage duration exceeds the closed range.")
        }
        val expectedMinutes = (exactDurationMillis / MILLIS_PER_MINUTE).coerceAtLeast(1L)
        require(minutes == expectedMinutes)
        val bucket = when {
            minutes >= 180 -> "long-session"
            minutes >= 30 -> "medium-session"
            else -> "short-session"
        }
        require(event.labels == listOf("app-usage", bucket))
        event.keywords.forEach { keyword -> require(CLOSED_TOKEN.matches(keyword)) }
    }

    private fun requireClosedText(value: String, maxBytes: Int) {
        require(value.isNotBlank() && value.toByteArray(UTF_8).size <= maxBytes)
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            require(Character.charCount(codePoint) != 1 || !value[index].isSurrogate())
            require(!Character.isISOControl(codePoint))
            require(
                Character.getType(codePoint) !in setOf(
                    Character.FORMAT.toInt(),
                    Character.PRIVATE_USE.toInt(),
                    Character.SURROGATE.toInt(),
                    Character.UNASSIGNED.toInt(),
                ),
            )
            index += Character.charCount(codePoint)
        }
    }

    private fun isCoordinateGridValue(value: Double): Boolean {
        val scaled = value * 1_000.0
        return value.isFinite() && abs(scaled - round(scaled)) < COORDINATE_GRID_EPSILON
    }

    private const val LOCATION = "location"
    private const val PHOTOS = "photos"
    private const val CALENDAR = "calendar"
    private const val NOTIFICATION = "notification"
    private const val APP_USAGE = "app_usage"
    private const val CALENDAR_SOURCE_APP = "android-calendar"
    private const val LOCATION_CITATION_PREFIX = "Location sample: "
    private const val CALENDAR_CITATION_PREFIX = "Calendar: "
    private const val APP_USAGE_CITATION_PREFIX = "App usage: "
    private const val MAX_LOCATION_KEYWORD_BYTES = 128
    private const val MAX_LOCATION_REGION_BYTES = 128
    private const val MAX_LOCATION_RADIUS_METERS = 100_000.0
    private const val MAX_CALENDAR_FIELD_BYTES = 384
    private const val MAX_PHOTO_DIMENSION = 100_000
    private const val MAX_APP_ALIAS_BYTES = 256
    private const val MAX_USAGE_MINUTES = 5_256_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MAX_PACKAGE_NAME_BYTES = 255
    private const val COORDINATE_GRID_EPSILON = 1e-7
    private val HASH = Regex("[a-f0-9]{32}")
    private val LOCATION_CLUSTER_ID = Regex("place-cluster:location:[a-f0-9]{32}")
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val CLOSED_TOKEN = Regex("[\\p{L}\\p{Nd}_-]{3,32}")
    private val CALENDAR_KEYWORD = Regex("[\\p{L}\\p{Nd}]{3,32}")
    private val LOCATION_PROVIDERS = setOf("fused", "gps", "network", "passive", "other")
    private val PHOTO_MIME_SUBTYPES = setOf("avif", "gif", "heic", "heif", "jpeg", "png", "webp")
    private val PHOTO_LABELS = PHOTO_MIME_SUBTYPES + setOf("landscape", "portrait")
    private val PHOTO_SUMMARY = Regex(
        "Photo metadata indexed at ([^ ]+)\\.(?: Type: image/([a-z]+)\\.)?(?: Dimensions: ([0-9]{1,6})x([0-9]{1,6})\\.)?",
    )
    private val WEATHER_SUFFIX = Regex(
        " Weather signal: WMO (?:0|1|2|3|45|48|51|53|55|56|57|61|63|65|66|67|71|73|75|77|80|81|82|85|86|95|96|99), " +
            "temperature -?[0-9]{1,3}\\.[0-9] C, precipitation [0-9]{1,4}\\.[0-9] mm\\.",
    )
    private val NOTIFICATION_KIND_LABELS = mapOf(
        DerivedMemoryEventKind.PAYMENT to setOf("payment"),
        DerivedMemoryEventKind.DELIVERY to setOf("delivery"),
        DerivedMemoryEventKind.RESERVATION to setOf("reservation"),
        DerivedMemoryEventKind.TRANSPORT to setOf("transport"),
        DerivedMemoryEventKind.INFERRED_CONTEXT to setOf("message-hint", "other"),
    )
    private val NOTIFICATION_CATEGORIES = setOf(
        "alarm",
        "call",
        "email",
        "err",
        "event",
        "location_sharing",
        "missed_call",
        "msg",
        "navigation",
        "progress",
        "promo",
        "recommendation",
        "reminder",
        "service",
        "social",
        "status",
        "stopwatch",
        "sys",
        "transport",
        "voicemail",
        "workout",
    )
}

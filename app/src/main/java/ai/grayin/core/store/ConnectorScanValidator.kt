package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult

internal object ConnectorScanValidator {
    fun validate(scanResult: ConnectorScanResult) {
        require(scanResult.connectorId.isNotBlank()) { "A connector scan must have a connector ID." }
        requireDistinctIds("source reference", scanResult.sourceReferences.map { it.id })
        requireDistinctIds("derived event", scanResult.derivedEvents.map { it.id })
        requireDistinctIds("citation", scanResult.citations.map { it.id })
        requireDistinctIds("place cluster", scanResult.placeClusters.map { it.id })
        requireDistinctIds("app usage summary", scanResult.appUsageSummaries.map { it.id })
        require(scanResult.sourceReferences.all { it.connectorId == scanResult.connectorId }) {
            "Every source reference in a connector scan must belong to that connector."
        }
        require(scanResult.sourceReferences.all { it.id.startsWith("source:${scanResult.connectorId}:") }) {
            "Every source reference ID must be connector-scoped."
        }
        require(scanResult.derivedEvents.all { it.id.startsWith("event:${scanResult.connectorId}:") }) {
            "Every derived event ID must be connector-scoped."
        }
        require(scanResult.citations.all { it.id.startsWith("citation:${scanResult.connectorId}:") }) {
            "Every citation ID must be connector-scoped."
        }
        require(scanResult.placeClusters.all { it.id.startsWith("place-cluster:${scanResult.connectorId}:") }) {
            "Every place-cluster ID must be connector-scoped."
        }
        require(scanResult.appUsageSummaries.all { it.id.startsWith("app-usage:${scanResult.connectorId}:") }) {
            "Every app-usage summary ID must be connector-scoped."
        }
        require(
            scanResult.missingSources.all { missing ->
                missing.connectorId == null || missing.connectorId == scanResult.connectorId
            },
        ) {
            "Every connector-scoped missing source must belong to the scan connector."
        }
        require(
            scanResult.missingSources.all { missing ->
                missing.explanation.isNotBlank() &&
                    missing.explanation.length <= MAX_MISSING_SOURCE_EXPLANATION_CHARS &&
                    missing.explanation.none(Char::isISOControl)
            },
        ) {
            "A connector scan missing-source explanation must be bounded, non-blank, and single-line."
        }

        val sourceIds = scanResult.sourceReferences.mapTo(mutableSetOf()) { it.id }
        val referencedSourceIds = scanResult.derivedEvents.flatMap { it.sourceReferenceIds } +
            scanResult.citations.map { it.sourceReferenceId } +
            scanResult.placeClusters.flatMap { it.sourceReferenceIds } +
            scanResult.appUsageSummaries.flatMap { it.sourceReferenceIds }
        require(referencedSourceIds.all { it in sourceIds }) {
            "A connector scan cannot persist dangling source references."
        }

        val eventIds = scanResult.derivedEvents.mapTo(mutableSetOf()) { it.id }
        val referencedEventIds = scanResult.citations.mapNotNull { it.derivedMemoryEventId }
        require(referencedEventIds.all { it in eventIds }) {
            "A connector scan cannot persist citations for missing derived events."
        }

        val citationIds = scanResult.citations.mapTo(mutableSetOf()) { it.id }
        val referencedCitationIds = scanResult.derivedEvents.flatMap { it.citationIds }
        require(referencedCitationIds.all { it in citationIds }) {
            "A connector scan cannot persist derived events with missing citations."
        }

        val citationsById = scanResult.citations.associateBy { it.id }
        val eventsById = scanResult.derivedEvents.associateBy { it.id }
        scanResult.derivedEvents.forEach { event ->
            event.citationIds.forEach { citationId ->
                val citation = checkNotNull(citationsById[citationId])
                require(citation.derivedMemoryEventId == event.id) {
                    "A citation must target the derived event that lists it."
                }
                require(citation.sourceReferenceId in event.sourceReferenceIds) {
                    "A citation source must be one of its derived event's sources."
                }
            }
        }
        scanResult.citations.forEach { citation ->
            citation.derivedMemoryEventId?.let { eventId ->
                require(citation.id in checkNotNull(eventsById[eventId]).citationIds) {
                    "A derived-event citation must be listed by its target event."
                }
            }
        }
    }

    private fun requireDistinctIds(label: String, ids: List<String>) {
        require(ids.all { it.isNotBlank() }) { "Every $label ID must be non-blank." }
        require(ids.distinct().size == ids.size) { "A connector scan contains duplicate $label IDs." }
    }

    private const val MAX_MISSING_SOURCE_EXPLANATION_CHARS = 240
}

package ai.grayin.core.indexing

import java.time.LocalDate
import java.time.ZoneId

/** User-selected inclusive local dates converted to the queue's half-open instant range. */
data class ManualIndexDateRange(
    val startDateInclusive: LocalDate,
    val endDateInclusive: LocalDate,
) {
    init {
        require(!endDateInclusive.isBefore(startDateInclusive)) {
            "Indexing end date must not precede the start date."
        }
        require(endDateInclusive != LocalDate.MAX) {
            "Indexing end date is outside the supported range."
        }
    }

    fun toCommand(
        connectorId: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): IndexDateRange {
        require(connectorId.isNotBlank()) { "Date-range indexing requires a connector ID." }
        return IndexDateRange(
            from = startDateInclusive.atStartOfDay(zoneId).toInstant(),
            until = endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant(),
            connectorId = connectorId,
        )
    }
}

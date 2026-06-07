package ai.grayin.core.retrieval

import ai.grayin.core.model.MemoryCapability
import java.time.Instant

interface QueryPlanner {
    fun plan(
        query: String,
        availableCapabilities: Set<MemoryCapability>,
        now: Instant = Instant.now(),
    ): QueryPlan
}

class DefaultQueryPlanner(
    private val timeParser: ApproximateTimeParser = ApproximateTimeParser(),
) : QueryPlanner {
    override fun plan(
        query: String,
        availableCapabilities: Set<MemoryCapability>,
        now: Instant,
    ): QueryPlan {
        val intent = detectIntent(query)
        val timeRange = timeParser.parse(query, now)
        return profileFor(intent).resolve(
            query = query,
            intent = intent,
            availableCapabilities = availableCapabilities,
            timeRange = timeRange,
        )
    }

    private fun detectIntent(query: String): QueryIntent {
        val normalized = query.lowercase()

        return when {
            normalized.containsAny("route", "1st", "2nd", "3rd") &&
                normalized.containsAny("drink", "drinking", "drank", "bar", "seoul") -> {
                QueryIntent.NIGHT_OUT_ROUTE_RECONSTRUCTION
            }

            normalized.containsAny("busy", "available", "free") &&
                normalized.containsAny("next week", "tomorrow", "next month") -> {
                QueryIntent.FUTURE_BUSYNESS_CHECK
            }

            normalized.containsAny("photo", "photos", "picture", "image", "food") -> {
                QueryIntent.PHOTO_RECALL
            }

            normalized.containsAny("meeting", "meetings", "calendar", "schedule") -> {
                QueryIntent.SCHEDULE_RECALL
            }

            normalized.containsAny("payment", "paid", "delivery", "reservation", "transport", "receipt") -> {
                QueryIntent.NOTIFICATION_PAYMENT_RECALL
            }

            normalized.containsAny("app usage", "screen time", "used my phone", "apps") -> {
                QueryIntent.APP_USAGE_RECALL
            }

            normalized.containsAny("where", "went", "go yesterday", "places", "place") -> {
                QueryIntent.LOCATION_RECALL
            }

            else -> QueryIntent.GENERAL_MEMORY_RECALL
        }
    }

    private fun profileFor(intent: QueryIntent): IntentCapabilityProfile {
        return when (intent) {
            QueryIntent.LOCATION_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
                optional = setOf(
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_CALENDAR,
                ),
            )

            QueryIntent.SCHEDULE_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
                optional = setOf(MemoryCapability.HAS_TEXT, MemoryCapability.HAS_APP_USAGE),
            )

            QueryIntent.FUTURE_BUSYNESS_CHECK -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_CALENDAR),
                optional = setOf(MemoryCapability.HAS_APP_USAGE, MemoryCapability.HAS_TEXT),
            )

            QueryIntent.PHOTO_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_MEDIA),
                optional = setOf(MemoryCapability.HAS_VISUAL_LABEL, MemoryCapability.HAS_LOCATION),
            )

            QueryIntent.NOTIFICATION_PAYMENT_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_PAYMENT),
                optional = setOf(
                    MemoryCapability.HAS_DELIVERY,
                    MemoryCapability.HAS_RESERVATION,
                    MemoryCapability.HAS_TRANSPORT,
                    MemoryCapability.HAS_TEXT,
                ),
            )

            QueryIntent.APP_USAGE_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_APP_USAGE),
            )

            QueryIntent.GENERAL_MEMORY_RECALL -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME),
                optional = setOf(
                    MemoryCapability.HAS_LOCATION,
                    MemoryCapability.HAS_CALENDAR,
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_APP_USAGE,
                    MemoryCapability.HAS_TEXT,
                    MemoryCapability.HAS_PERSON,
                ),
            )

            QueryIntent.NIGHT_OUT_ROUTE_RECONSTRUCTION -> IntentCapabilityProfile(
                required = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
                optional = setOf(
                    MemoryCapability.HAS_PAYMENT,
                    MemoryCapability.HAS_MEDIA,
                    MemoryCapability.HAS_TEXT,
                    MemoryCapability.HAS_APP_USAGE,
                    MemoryCapability.HAS_TRANSPORT,
                ),
            )
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it) }
    }
}


package ai.grayin.core.retrieval

import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.SourceAvailability

data class QueryPlan(
    val query: String,
    val intent: QueryIntent,
    val timeRange: ApproximateTimeRange? = null,
    val requiredCapabilities: Set<MemoryCapability>,
    val optionalCapabilities: Set<MemoryCapability>,
    val availableCapabilities: Set<MemoryCapability>,
    val missingRequiredCapabilities: Set<MemoryCapability>,
    val missingOptionalCapabilities: Set<MemoryCapability>,
    val missingSources: List<MissingSource>,
)

data class IntentCapabilityProfile(
    val required: Set<MemoryCapability>,
    val optional: Set<MemoryCapability> = emptySet(),
) {
    fun resolve(
        query: String,
        intent: QueryIntent,
        availableCapabilities: Set<MemoryCapability>,
        timeRange: ApproximateTimeRange?,
    ): QueryPlan {
        val missingRequired = required - availableCapabilities
        val missingOptional = optional - availableCapabilities
        val missingSources = missingRequired.map { capability ->
            MissingSource(
                capability = capability,
                availability = SourceAvailability.NOT_INDEXED,
                explanation = "Required capability ${capability.name} is unavailable for ${intent.name}.",
            )
        } + missingOptional.map { capability ->
            MissingSource(
                capability = capability,
                availability = SourceAvailability.NOT_INDEXED,
                explanation = "Optional capability ${capability.name} is unavailable for ${intent.name}.",
            )
        }

        return QueryPlan(
            query = query,
            intent = intent,
            timeRange = timeRange,
            requiredCapabilities = required,
            optionalCapabilities = optional,
            availableCapabilities = availableCapabilities,
            missingRequiredCapabilities = missingRequired,
            missingOptionalCapabilities = missingOptional,
            missingSources = missingSources,
        )
    }
}


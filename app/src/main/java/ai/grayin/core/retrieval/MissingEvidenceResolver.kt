package ai.grayin.core.retrieval

import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.SourceAvailability

object MissingEvidenceResolver {
    fun resolve(
        plan: QueryPlan,
        hasEvidence: Boolean,
        noMatchingEvidenceExplanation: String,
    ): List<MissingSource> {
        if (hasEvidence) return plan.missingSources
        val reportedCapabilities = plan.missingSources.mapTo(hashSetOf()) { source -> source.capability }
        val unmatchedRequiredSources = plan.requiredCapabilities
            .filter { capability -> capability !in reportedCapabilities }
            .map { capability ->
                MissingSource(
                    capability = capability,
                    availability = SourceAvailability.NOT_INDEXED,
                    explanation = noMatchingEvidenceExplanation,
                )
            }
        return plan.missingSources + unmatchedRequiredSources
    }
}

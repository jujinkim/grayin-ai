package ai.grayin.core.retrieval

import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCapability

object EvidenceEventSelector {
    fun select(
        query: String,
        plan: QueryPlan,
        events: List<DerivedMemoryEvent>,
        limit: Int,
    ): List<DerivedMemoryEvent> {
        val queryTokens = tokenize(query)
        val requiredDomainCapabilities = plan.requiredCapabilities - MemoryCapability.HAS_TIME
        val relevantDomainCapabilities =
            (plan.requiredCapabilities + plan.optionalCapabilities) - MemoryCapability.HAS_TIME
        val domainCandidates = if (relevantDomainCapabilities.isEmpty()) {
            events
        } else {
            events.filter { event ->
                MemoryCapabilityResolver.forEvent(event).any { capability ->
                    capability in relevantDomainCapabilities
                }
            }
        }
        val scored = domainCandidates.map { event -> event to score(event, queryTokens) }
        if (scored.any { (_, score) -> score > 0 }) {
            val positiveEvents = scored.filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .map { (event) -> event }
            val requiredSupport = if (
                requiredDomainCapabilities.isEmpty() ||
                positiveEvents.any { event ->
                    MemoryCapabilityResolver.forEvent(event).containsAll(requiredDomainCapabilities)
                }
            ) {
                emptyList()
            } else {
                domainCandidates.filter { event ->
                    MemoryCapabilityResolver.forEvent(event).containsAll(requiredDomainCapabilities)
                }.sortedByDescending { event -> event.createdAt }.take(1)
            }
            return (positiveEvents + requiredSupport).distinctBy { event -> event.id }.take(limit)
        }

        val requiredCandidates = if (requiredDomainCapabilities.isEmpty()) {
            domainCandidates
        } else {
            domainCandidates.filter { event ->
                MemoryCapabilityResolver.forEvent(event).containsAll(requiredDomainCapabilities)
            }
        }
        return requiredCandidates.sortedByDescending { event -> event.createdAt }.take(limit)
    }

    private fun score(event: DerivedMemoryEvent, queryTokens: Set<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val keywordScore = event.keywords.count { keyword -> keyword.lowercase() in queryTokens } * 3
        val labelScore = event.labels.count { label -> label.lowercase() in queryTokens }
        val summary = event.summary.lowercase()
        val summaryScore = queryTokens.count { token -> summary.contains(token) }
        return keywordScore + labelScore + summaryScore
    }

    private fun tokenize(value: String): Set<String> {
        return Regex("[\\p{L}\\p{Nd}]+").findAll(value.lowercase())
            .map { match -> match.value }
            .filter { token -> token.length >= MIN_LATIN_TOKEN_LENGTH || token.any { it.code > ASCII_MAX } }
            .toSet()
    }

    private const val MIN_LATIN_TOKEN_LENGTH = 3
    private const val ASCII_MAX = 127
}

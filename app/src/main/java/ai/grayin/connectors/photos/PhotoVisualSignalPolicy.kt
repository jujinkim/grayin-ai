package ai.grayin.connectors.photos

import ai.grayin.core.model.ConfidenceLevel

/**
 * The only visual-model output shape that may later cross into derived memory. Captions, OCR,
 * embeddings, pixels, crops, and arbitrary model text are intentionally not representable.
 */
internal data class ClosedPhotoVisualSignals(
    val labels: List<String>,
    val confidence: ConfidenceLevel,
)

internal object PhotoVisualSignalPolicy {
    fun close(labels: Iterable<String>, confidence: ConfidenceLevel): ClosedPhotoVisualSignals? {
        if (confidence !in setOf(ConfidenceLevel.MEDIUM, ConfidenceLevel.HIGH)) return null
        val accepted = labels
            .map { label -> label.trim().lowercase() }
            .filter(ALLOWED_LABELS::contains)
            .distinct()
        val closed = accepted
            .sortedBy(LABEL_PRIORITY::getValue)
            .take(MAX_LABELS)
        return closed.takeIf(List<String>::isNotEmpty)?.let { values ->
            ClosedPhotoVisualSignals(values, confidence)
        }
    }

    private const val MAX_LABELS = 3
    private val LABEL_PRIORITY = listOf(
        "food",
        "drink",
        "document",
        "person-present",
        "indoor",
        "outdoor",
    ).withIndex().associate { (index, label) -> label to index }
    private val ALLOWED_LABELS = LABEL_PRIORITY.keys
}

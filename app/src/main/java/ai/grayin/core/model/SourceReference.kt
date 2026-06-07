package ai.grayin.core.model

import java.time.Instant

data class SourceReference(
    val id: String,
    val connectorId: String,
    val sourceKind: SourceKind,
    val localPointer: String? = null,
    val externalIdHash: String? = null,
    val hmacHash: String? = null,
    val sourceAppIdentifier: String? = null,
    val observedAt: Instant? = null,
    val modifiedAt: Instant? = null,
    val sensitivity: SensitivityLevel = SensitivityLevel.MEDIUM,
)


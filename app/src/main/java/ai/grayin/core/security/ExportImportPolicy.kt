package ai.grayin.core.security

import java.time.Instant

enum class ExportDataSection {
    SOURCE_REFERENCES,
    DERIVED_MEMORY_EVENTS,
    CITATIONS,
    DAILY_SUMMARIES,
    PLACE_CLUSTERS,
    APP_USAGE_SUMMARIES,
    CONNECTOR_SCAN_STATUSES,
}

enum class ForbiddenExportData {
    PHOTO_ORIGINALS,
    PDF_ORIGINALS,
    NOTIFICATION_ORIGINALS,
    MESSAGE_ORIGINALS,
    USAGE_LOG_ORIGINALS,
    CALENDAR_RECORD_ORIGINALS,
    LOCAL_FILE_ORIGINALS,
    AUDIO_VIDEO_ORIGINALS,
    LOCAL_SOURCE_POINTERS,
    DATABASE_AND_KEY_MATERIAL,
    CONNECTOR_CONSENT_AND_SETTINGS,
    INDEXING_QUEUE_AND_RUNTIME,
    MODEL_AND_OCR_ARTIFACTS,
    PROMPTS_ANSWERS_AND_EVIDENCE_PACKS,
}

data class EncryptedExportFormat(
    val formatVersion: Int = 1,
    val encryptionScheme: String,
    val keyProtection: String,
    val allowedSections: Set<ExportDataSection>,
    val forbiddenData: Set<ForbiddenExportData>,
    val createdAt: Instant,
)

data class ImportConsentRequirement(
    val connectorId: String,
    val requiresReConsent: Boolean = true,
    val explanation: String,
)

data class ExportImportPolicy(
    val format: EncryptedExportFormat,
    val importConsentRequirements: List<ImportConsentRequirement>,
) {
    fun isAllowed(section: ExportDataSection): Boolean = section in format.allowedSections

    fun isForbidden(data: ForbiddenExportData): Boolean = data in format.forbiddenData

    companion object {
        val ALLOWED_DERIVED_SECTIONS: Set<ExportDataSection> = ExportDataSection.entries.toSet()
        val FORBIDDEN_DATA: Set<ForbiddenExportData> = ForbiddenExportData.entries.toSet()
    }
}

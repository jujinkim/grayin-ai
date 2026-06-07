package ai.grayin.core.security

import java.time.Instant

enum class ExportDataSection {
    SOURCE_REFERENCES,
    DERIVED_MEMORY_EVENTS,
    CITATIONS,
    DAILY_SUMMARIES,
    PLACE_CLUSTERS,
    APP_USAGE_SUMMARIES,
    INDEX_METADATA,
    CONNECTOR_METADATA,
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
}


package ai.grayin.core.model

enum class SourceKind {
    LOCATION,
    PHOTO,
    CALENDAR,
    NOTIFICATION,
    APP_USAGE,
    LOCAL_FILE,
    MARKDOWN_NOTE,
    PDF_PAGE,
    OCR_TEXT,
    LOCAL_LLM_SUMMARY,
}

enum class DerivedMemoryEventKind {
    PLACE_VISIT,
    PLACE_CLUSTER,
    CALENDAR_EVENT,
    PHOTO_INDEX,
    PHOTO_CLUSTER,
    PAYMENT,
    DELIVERY,
    RESERVATION,
    TRANSPORT,
    APP_USAGE,
    LOCAL_FILE_INDEX,
    DAILY_SUMMARY,
    INFERRED_CONTEXT,
}

enum class SensitivityLevel {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH,
}

enum class ConfidenceLevel {
    UNKNOWN,
    LOW,
    MEDIUM,
    HIGH,
}

enum class ProcessingState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    STALE,
}

enum class SourceAvailability {
    AVAILABLE,
    DISABLED,
    DENIED,
    UNSUPPORTED,
    NOT_INDEXED,
    STALE,
    MISSING_PERMISSION,
}

enum class ConnectorScanIssueCode(
    val storageKey: String,
    val defaultEnglish: String,
) {
    SOURCE_PERMISSION_NOT_GRANTED("source_permission_not_granted", "Source permission was not granted."),
    SOURCE_NOT_INVOKED("source_not_invoked", "Source has not been invoked."),
    SOURCE_UNAVAILABLE("source_unavailable", "The source is unavailable for this scan."),
    NO_CALENDAR_EVENTS_IN_RANGE(
        "no_calendar_events_in_range",
        "No calendar events were found in the indexed time window.",
    ),
    NO_PHOTOS_IN_RANGE("no_photos_in_range", "No photos were found in the indexed time window."),
    NO_APP_USAGE_IN_RANGE("no_app_usage_in_range", "No app usage events were found in the indexed time window."),
    NO_LAST_KNOWN_LOCATION("no_last_known_location", "No last known location sample is available."),
    NOTIFICATION_ALLOWLIST_EMPTY(
        "notification_allowlist_empty",
        "Add at least one application to the notification allowlist.",
    ),
    NOTIFICATION_HISTORY_UNAVAILABLE(
        "notification_history_unavailable",
        "Notifications are indexed only when new allowlisted notifications arrive.",
    ),
    NO_LOCAL_DOCUMENTS_SELECTED("no_local_documents_selected", "No local documents are selected."),
    LOCAL_DOCUMENT_PERMISSION_REVOKED(
        "local_document_permission_revoked",
        "Read access was revoked for a selected document.",
    ),
    LOCAL_DOCUMENT_TYPE_UNSUPPORTED(
        "local_document_type_unsupported",
        "The selected document type is unsupported.",
    ),
    LOCAL_DOCUMENT_READ_FAILED("local_document_read_failed", "A selected document could not be read."),
    DOCUMENT_FILE_TOO_LARGE("document_file_too_large", "The selected document exceeds the size limit."),
    DOCUMENT_SIZE_UNKNOWN("document_size_unknown", "The selected document size could not be verified."),
    DOCUMENT_NOT_SEEKABLE(
        "document_not_seekable",
        "The selected document does not support safe random access.",
    ),
    PDF_PAGE_LIMIT_EXCEEDED("pdf_page_limit_exceeded", "The PDF exceeds the page limit."),
    PDF_PASSWORD_REQUIRED("pdf_password_required", "The PDF requires a password."),
    PDF_MALFORMED("pdf_malformed", "The PDF is malformed or unsupported."),
    PDF_PAGE_DIMENSIONS_UNSUPPORTED(
        "pdf_page_dimensions_unsupported",
        "A PDF page has unsupported dimensions.",
    ),
    OCR_MODEL_UNAVAILABLE(
        "ocr_model_unavailable",
        "The required on-device OCR language data is not installed.",
    ),
    OCR_PAGE_LIMIT_REACHED("ocr_page_limit_reached", "The PDF exceeds the per-document OCR page limit."),
    OCR_TIMED_OUT("ocr_timed_out", "On-device OCR exceeded its time limit."),
    DOCUMENT_PROCESS_CRASHED(
        "document_process_crashed",
        "The isolated document processor stopped unexpectedly.",
    ),
    NO_EXTRACTABLE_TEXT("no_extractable_text", "No extractable text was found in the document."),
    PARTIAL_DOCUMENT_INDEX("partial_document_index", "Only part of the document could be indexed."),
    ;

    companion object {
        private val byStorageKey = entries.associateBy(ConnectorScanIssueCode::storageKey)
        private val legacyExplanations = mapOf(
            "Calendar permission was not granted." to SOURCE_PERMISSION_NOT_GRANTED,
            "Location permission was not granted." to SOURCE_PERMISSION_NOT_GRANTED,
            "Photo permission was not granted." to SOURCE_PERMISSION_NOT_GRANTED,
            "Usage access was not granted." to SOURCE_PERMISSION_NOT_GRANTED,
            "Notification listener access was not granted." to SOURCE_PERMISSION_NOT_GRANTED,
            "Calendar source has not been invoked." to SOURCE_NOT_INVOKED,
            "Location source has not been invoked." to SOURCE_NOT_INVOKED,
            "Photos source has not been invoked." to SOURCE_NOT_INVOKED,
            "App Usage source has not been invoked." to SOURCE_NOT_INVOKED,
            "Notifications source has not been invoked." to SOURCE_NOT_INVOKED,
            "No calendar events found in the indexed time window." to NO_CALENDAR_EVENTS_IN_RANGE,
            "No photos found in the indexed time window." to NO_PHOTOS_IN_RANGE,
            "No app usage events found in the indexed time window." to NO_APP_USAGE_IN_RANGE,
            "No last known location sample is available." to NO_LAST_KNOWN_LOCATION,
            "Add at least one application package to the notification allowlist." to NOTIFICATION_ALLOWLIST_EMPTY,
            "Notification source indexes allowlisted new notifications as they arrive; there is no historical notification scan." to
                NOTIFICATION_HISTORY_UNAVAILABLE,
            "No local text or Markdown files selected." to NO_LOCAL_DOCUMENTS_SELECTED,
            "Read access was revoked for selected file." to LOCAL_DOCUMENT_PERMISSION_REVOKED,
            "Only .txt and .md files are supported in this milestone." to LOCAL_DOCUMENT_TYPE_UNSUPPORTED,
        )

        fun fromStorageKey(storageKey: String): ConnectorScanIssueCode? = byStorageKey[storageKey]

        fun fromLegacyExplanation(explanation: String): ConnectorScanIssueCode? {
            return legacyExplanations[explanation] ?: entries.firstOrNull { it.defaultEnglish == explanation }
        }
    }
}

enum class ConnectorCapability {
    LOCATION,
    PHOTOS,
    CALENDAR,
    NOTIFICATIONS,
    APP_USAGE,
    LOCAL_FILES,
}

enum class MemoryCapability {
    HAS_TIME,
    HAS_LOCATION,
    HAS_MEDIA,
    HAS_CALENDAR,
    HAS_PAYMENT,
    HAS_DELIVERY,
    HAS_RESERVATION,
    HAS_TRANSPORT,
    HAS_APP_USAGE,
    HAS_TEXT,
    HAS_PERSON,
    HAS_VISUAL_LABEL,
}

enum class NotificationDerivedEventKind {
    PAYMENT,
    DELIVERY,
    RESERVATION,
    TRANSPORT,
    MESSAGE_HINT,
    SECURITY_HINT,
    OTHER,
}

enum class AppUsageCategory {
    WORK,
    STUDY,
    ENTERTAINMENT,
    COMMUNICATION,
    FINANCE,
    HEALTH,
    TRAVEL,
    OTHER,
}

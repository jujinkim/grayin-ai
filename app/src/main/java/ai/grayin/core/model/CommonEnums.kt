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


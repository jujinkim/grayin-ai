package ai.grayin.app

import android.content.Context
import ai.grayin.core.ai.ModelDownloadStatus
import java.util.Locale

enum class GrayinLanguageOption(val storageKey: String) {
    SYSTEM("system"),
    KOREAN("korean"),
    ENGLISH("english"),
    JAPANESE("japanese"),
    ;

    companion object {
        fun fromStorageKey(storageKey: String?): GrayinLanguageOption {
            return entries.firstOrNull { it.storageKey == storageKey } ?: SYSTEM
        }
    }
}

enum class GrayinLanguage {
    KOREAN,
    ENGLISH,
    JAPANESE,
}

object GrayinLanguageResolver {
    fun resolve(
        option: GrayinLanguageOption,
        systemLanguage: String = Locale.getDefault().language,
    ): GrayinLanguage {
        return when (option) {
            GrayinLanguageOption.KOREAN -> GrayinLanguage.KOREAN
            GrayinLanguageOption.ENGLISH -> GrayinLanguage.ENGLISH
            GrayinLanguageOption.JAPANESE -> GrayinLanguage.JAPANESE
            GrayinLanguageOption.SYSTEM -> when (systemLanguage.lowercase(Locale.ROOT)) {
                "ko" -> GrayinLanguage.KOREAN
                "ja" -> GrayinLanguage.JAPANESE
                else -> GrayinLanguage.ENGLISH
            }
        }
    }
}

class LanguagePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GrayinLanguageOption {
        return GrayinLanguageOption.fromStorageKey(prefs.getString(KEY_LANGUAGE, null))
    }

    fun save(option: GrayinLanguageOption) {
        prefs.edit().putString(KEY_LANGUAGE, option.storageKey).apply()
    }

    private companion object {
        const val PREFS_NAME = "grayin_language"
        const val KEY_LANGUAGE = "language"
    }
}

data class GrayinStrings(
    val languageCode: GrayinLanguage,
    val ask: String,
    val timeline: String,
    val places: String,
    val sources: String,
    val settings: String,
    val memoryQuestion: String,
    val search: String,
    val searching: String,
    val answer: String,
    val confidencePrefix: String,
    val evidence: String,
    val show: String,
    val hide: String,
    val missingData: String,
    val sourceConnectionTitle: String,
    val sourceConnectionBody: String,
    val sourceConnectionPrivacyNote: String,
    val invokeSource: String,
    val addLocalFile: String,
    val revoke: String,
    val delete: String,
    val indexNow: String,
    val indexAllNow: String,
    val indexing: String,
    val automaticIndexing: String,
    val automaticIndexingSettings: String,
    val automaticIndexingOn: String,
    val automaticIndexingOff: String,
    val chargingOnly: String,
    val startHour: String,
    val endHour: String,
    val language: String,
    val localFiles: String,
    val location: String,
    val photos: String,
    val calendar: String,
    val notifications: String,
    val notificationAllowlistTitle: String,
    val notificationAllowlistHint: String,
    val saveNotificationAllowlist: String,
    val notificationAllowlistInvalid: String,
    val appUsage: String,
    val off: String,
    val selected: String,
    val indexed: String,
    val notImplemented: String,
    val connectorConnectionUnavailable: String,
    val highSensitivity: String,
    val veryHighSensitivity: String,
    val noDerivedEvents: String,
    val noPlaceClusters: String,
    val loadingLocalState: String,
    val noAnswerAvailable: String,
    val cannotAnswerFromIndexedEvidence: String,
    val noCitedEvidence: String,
    val addAndIndexLocalFileFirst: String,
    val tryIndexingAgain: String,
    val noMissingSources: String,
    val enterMemoryQuestion: String,
    val noLocalTextEvidenceIndexed: String,
    val askFromIndexedEvidence: String,
    val localFileSelectionFailed: String,
    val revokeFailed: String,
    val deleteFailed: String,
    val indexingFailed: String,
    val searchFailed: String,
    val selectedLocalFile: String,
    val unsupportedFileOrPermissionDenied: String,
    val sourcePermissionDenied: String,
    val noLocalFilesIndexed: String,
    val noSourcesReadyToIndex: String,
    val revokedLocalFiles: String,
    val networkPermissionRestricted: String,
    val accountAbsent: String,
    val cloudSyncAbsent: String,
    val telemetryAbsent: String,
    val crashAnalyticsAbsent: String,
    val encryptedStoreSqlCipher: String,
    val localGemmaModelTitle: String,
    val localGemmaModelReady: String,
    val localGemmaModelLoading: String,
    val localGemmaModelUnavailable: String,
    val localGemmaModelApkNotBundled: String,
    val localGemmaModelDownloadGuide: String,
    val localGemmaModelDownloadUrl: String,
    val localGemmaModelFileGuide: String,
    val localGemmaModelDevInstallGuide: String,
    val openLocalGemmaModelDownloadPage: String,
    val importLocalGemmaModel: String,
    val deleteLocalGemmaModel: String,
    val localGemmaModelImported: String,
    val localGemmaModelDeleted: String,
    val localGemmaModelDeleteMissing: String,
    val localGemmaModelImportFailed: String,
    val localGemmaModelDownloadOpenFailed: String,
    val localGemmaModelInvalidFile: String,
    val localModelCatalogTitle: String,
    val localModelSelectedBadge: String,
    val localModelRecommended: String,
    val localModelSelect: String,
    val localModelDownload: String,
    val localModelCancelDownload: String,
    val localModelDeleteDownloaded: String,
    val localModelOpenDownloadPage: String,
    val localModelDownloadUnavailable: String,
    val localModelRequiresUnmeteredNetwork: String,
    val localModelUnknown: String,
    val indexedSourceReferencesPrefix: String,
    val derivedMemoryEventsPrefix: String,
) {
    fun screenLabel(screen: GrayinScreen): String {
        return when (screen) {
            GrayinScreen.Ask -> ask
            GrayinScreen.Timeline -> timeline
            GrayinScreen.Places -> places
            GrayinScreen.Sources -> sources
            GrayinScreen.Settings -> settings
        }
    }

    fun connectorName(connectorId: String, fallback: String): String {
        return when (connectorId) {
            "location" -> location
            "photos" -> photos
            "calendar" -> calendar
            "notification" -> notifications
            "app_usage" -> appUsage
            "local_files" -> localFiles
            else -> fallback
        }
    }

    fun itemCount(count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "${count}개"
            GrayinLanguage.JAPANESE -> "${count}件"
            GrayinLanguage.ENGLISH -> if (count == 1) "1 item" else "$count items"
        }
    }

    fun indexedLocalFileEvents(count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "로컬 파일 이벤트 ${count}개를 인덱싱했습니다."
            GrayinLanguage.JAPANESE -> "ローカルファイルイベントを${count}件インデックスしました。"
            GrayinLanguage.ENGLISH -> "Indexed $count local file event(s)."
        }
    }

    fun deletedLocalFileEvents(count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "로컬 파일 이벤트 ${count}개를 삭제했습니다."
            GrayinLanguage.JAPANESE -> "ローカルファイルイベントを${count}件削除しました。"
            GrayinLanguage.ENGLISH -> "Deleted $count local file event(s)."
        }
    }

    fun deletedConnectorEvents(connectorName: String, count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$connectorName 파생 이벤트 ${count}개를 삭제했습니다."
            GrayinLanguage.JAPANESE -> "$connectorName の派生イベントを${count}件削除しました。"
            GrayinLanguage.ENGLISH -> "Deleted $count derived event(s) for $connectorName."
        }
    }

    fun indexedConnectorEvents(connectorName: String, count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$connectorName 파생 이벤트 ${count}개를 인덱싱했습니다."
            GrayinLanguage.JAPANESE -> "$connectorName の派生イベントを${count}件インデックスしました。"
            GrayinLanguage.ENGLISH -> "Indexed $count derived event(s) for $connectorName."
        }
    }

    fun connectedConnector(connectorName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$connectorName 소스를 연결했습니다. 지금 인덱싱을 실행하세요."
            GrayinLanguage.JAPANESE -> "$connectorName ソースを接続しました。今すぐインデックスしてください。"
            GrayinLanguage.ENGLISH -> "Connected $connectorName. Run Index now."
        }
    }

    fun indexedAllSources(eventCount: Int, sourceCount: Int, skippedCount: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "소스 ${sourceCount}개에서 파생 이벤트 ${eventCount}개를 인덱싱했습니다. 건너뜀 ${skippedCount}개."
            GrayinLanguage.JAPANESE -> "ソース${sourceCount}件から派生イベント${eventCount}件をインデックスしました。スキップ${skippedCount}件。"
            GrayinLanguage.ENGLISH -> "Indexed $eventCount derived event(s) from $sourceCount source(s). Skipped $skippedCount."
        }
    }

    fun automaticIndexingSaved(enabled: Boolean): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> if (enabled) "자동 인덱싱 설정을 켰습니다." else "자동 인덱싱 설정을 껐습니다."
            GrayinLanguage.JAPANESE -> if (enabled) "自動インデックス設定をオンにしました。" else "自動インデックス設定をオフにしました。"
            GrayinLanguage.ENGLISH -> if (enabled) "Automatic indexing settings enabled." else "Automatic indexing settings disabled."
        }
    }

    fun notificationAllowlistSaved(count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "알림 앱 허용 목록을 저장했습니다: ${count}개."
            GrayinLanguage.JAPANESE -> "通知アプリ許可リストを保存しました: ${count}件。"
            GrayinLanguage.ENGLISH -> "Saved notification app allowlist: $count package(s)."
        }
    }

    fun automaticIndexingSummary(state: AutomaticIndexingUiState): String {
        val enabledLabel = if (state.enabled) automaticIndexingOn else automaticIndexingOff
        val chargingLabel = if (state.requireCharging) " · $chargingOnly" else ""
        return "$enabledLabel · ${state.windowLabel()}$chargingLabel"
    }

    fun automaticIndexingWindow(windowLabel: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "인덱싱 시간대: $windowLabel"
            GrayinLanguage.JAPANESE -> "インデックス時間帯: $windowLabel"
            GrayinLanguage.ENGLISH -> "Indexing window: $windowLabel"
        }
    }

    fun localModelStatus(status: ModelDownloadStatus): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (status) {
                ModelDownloadStatus.NOT_DOWNLOADED -> "상태: 다운로드 필요"
                ModelDownloadStatus.QUEUED -> "상태: 다운로드 대기 중"
                ModelDownloadStatus.DOWNLOADING -> "상태: 다운로드 중"
                ModelDownloadStatus.READY -> "상태: 준비됨"
                ModelDownloadStatus.FAILED -> "상태: 실패"
            }

            GrayinLanguage.JAPANESE -> when (status) {
                ModelDownloadStatus.NOT_DOWNLOADED -> "状態: ダウンロードが必要"
                ModelDownloadStatus.QUEUED -> "状態: ダウンロード待機中"
                ModelDownloadStatus.DOWNLOADING -> "状態: ダウンロード中"
                ModelDownloadStatus.READY -> "状態: 準備完了"
                ModelDownloadStatus.FAILED -> "状態: 失敗"
            }

            GrayinLanguage.ENGLISH -> when (status) {
                ModelDownloadStatus.NOT_DOWNLOADED -> "Status: download required"
                ModelDownloadStatus.QUEUED -> "Status: queued"
                ModelDownloadStatus.DOWNLOADING -> "Status: downloading"
                ModelDownloadStatus.READY -> "Status: ready"
                ModelDownloadStatus.FAILED -> "Status: failed"
            }
        }
    }

    fun localModelProvider(provider: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "제공처: $provider"
            GrayinLanguage.JAPANESE -> "提供元: $provider"
            GrayinLanguage.ENGLISH -> "Provider: $provider"
        }
    }

    fun localModelApproxSize(size: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "예상 크기: $size"
            GrayinLanguage.JAPANESE -> "推定サイズ: $size"
            GrayinLanguage.ENGLISH -> "Approx size: $size"
        }
    }

    fun localModelLicense(license: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "라이선스: $license"
            GrayinLanguage.JAPANESE -> "ライセンス: $license"
            GrayinLanguage.ENGLISH -> "License: $license"
        }
    }

    fun localModelInstalledSize(size: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "설치 크기: $size"
            GrayinLanguage.JAPANESE -> "インストールサイズ: $size"
            GrayinLanguage.ENGLISH -> "Installed size: $size"
        }
    }

    fun localModelRecommendedRam(gb: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "권장 RAM: ${gb}GB 이상"
            GrayinLanguage.JAPANESE -> "推奨RAM: ${gb}GB以上"
            GrayinLanguage.ENGLISH -> "Recommended RAM: ${gb}GB+"
        }
    }

    fun localModelFileName(fileName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "파일: $fileName"
            GrayinLanguage.JAPANESE -> "ファイル: $fileName"
            GrayinLanguage.ENGLISH -> "File: $fileName"
        }
    }

    fun localModelProgress(progressPercent: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "진행률: ${progressPercent}%"
            GrayinLanguage.JAPANESE -> "進行率: ${progressPercent}%"
            GrayinLanguage.ENGLISH -> "Progress: ${progressPercent}%"
        }
    }

    fun localModelFailure(reason: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "실패 사유: $reason"
            GrayinLanguage.JAPANESE -> "失敗理由: $reason"
            GrayinLanguage.ENGLISH -> "Failure: $reason"
        }
    }

    fun localModelSelected(modelName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$modelName 모델을 선택했습니다."
            GrayinLanguage.JAPANESE -> "$modelName モデルを選択しました。"
            GrayinLanguage.ENGLISH -> "Selected $modelName."
        }
    }

    fun localModelDownloadQueued(modelName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$modelName 다운로드를 대기열에 추가했습니다. Wi-Fi에서 실행됩니다."
            GrayinLanguage.JAPANESE -> "$modelName のダウンロードをキューに追加しました。Wi-Fiで実行します。"
            GrayinLanguage.ENGLISH -> "Queued $modelName download. It runs on Wi-Fi."
        }
    }

    fun localModelDownloadCanceled(modelName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$modelName 다운로드를 취소했습니다."
            GrayinLanguage.JAPANESE -> "$modelName のダウンロードをキャンセルしました。"
            GrayinLanguage.ENGLISH -> "Canceled $modelName download."
        }
    }

    fun localModelDeleted(modelName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$modelName 다운로드 파일을 삭제했습니다."
            GrayinLanguage.JAPANESE -> "$modelName のダウンロード済みファイルを削除しました。"
            GrayinLanguage.ENGLISH -> "Deleted downloaded $modelName."
        }
    }

    fun localModelDeleteMissing(modelName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$modelName 다운로드 파일이 없습니다."
            GrayinLanguage.JAPANESE -> "$modelName のダウンロード済みファイルはありません。"
            GrayinLanguage.ENGLISH -> "No downloaded $modelName file found."
        }
    }

    fun revokedConnector(connectorName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$connectorName 소스를 해제하고 파생 데이터를 삭제했습니다."
            GrayinLanguage.JAPANESE -> "$connectorName ソースを解除し、派生データを削除しました。"
            GrayinLanguage.ENGLISH -> "Revoked $connectorName and deleted derived data."
        }
    }

    fun languageOptionLabel(option: GrayinLanguageOption): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (option) {
                GrayinLanguageOption.SYSTEM -> "시스템"
                GrayinLanguageOption.KOREAN -> "한국어"
                GrayinLanguageOption.ENGLISH -> "영어"
                GrayinLanguageOption.JAPANESE -> "일본어"
            }

            GrayinLanguage.JAPANESE -> when (option) {
                GrayinLanguageOption.SYSTEM -> "システム"
                GrayinLanguageOption.KOREAN -> "韓国語"
                GrayinLanguageOption.ENGLISH -> "英語"
                GrayinLanguageOption.JAPANESE -> "日本語"
            }

            GrayinLanguage.ENGLISH -> when (option) {
                GrayinLanguageOption.SYSTEM -> "System"
                GrayinLanguageOption.KOREAN -> "Korean"
                GrayinLanguageOption.ENGLISH -> "English"
                GrayinLanguageOption.JAPANESE -> "Japanese"
            }
        }
    }

    fun capabilityUnavailable(capabilityName: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$capabilityName 기능을 현재 사용할 수 없습니다."
            GrayinLanguage.JAPANESE -> "$capabilityName 機能は現在利用できません。"
            GrayinLanguage.ENGLISH -> "Capability $capabilityName is unavailable."
        }
    }
}

object GrayinText {
    fun forOption(option: GrayinLanguageOption): GrayinStrings {
        return when (GrayinLanguageResolver.resolve(option)) {
            GrayinLanguage.KOREAN -> KoreanStrings
            GrayinLanguage.ENGLISH -> EnglishStrings
            GrayinLanguage.JAPANESE -> JapaneseStrings
        }
    }
}

private val EnglishStrings = GrayinStrings(
    languageCode = GrayinLanguage.ENGLISH,
    ask = "Ask",
    timeline = "Timeline",
    places = "Places",
    sources = "Sources",
    settings = "Settings",
    memoryQuestion = "Memory question",
    search = "Search",
    searching = "Searching",
    answer = "Answer",
    confidencePrefix = "Confidence:",
    evidence = "Evidence",
    show = "Show",
    hide = "Hide",
    missingData = "Missing data",
    sourceConnectionTitle = "Connect sources before asking",
    sourceConnectionBody = "Grayin can read and analyze only sources you connect here. Select a source, then run indexing so Ask can use derived evidence.",
    sourceConnectionPrivacyNote = "Indexing reads originals transiently and stores derived memory, citations, and source references only.",
    invokeSource = "Connect source",
    addLocalFile = "Add local file",
    revoke = "Revoke",
    delete = "Delete",
    indexNow = "Index now",
    indexAllNow = "Index all now",
    indexing = "Indexing",
    automaticIndexing = "Automatic indexing",
    automaticIndexingSettings = "Automatic indexing settings",
    automaticIndexingOn = "On",
    automaticIndexingOff = "Off",
    chargingOnly = "Only while charging",
    startHour = "Start hour",
    endHour = "End hour",
    language = "Language",
    localFiles = "Local files",
    location = "Location",
    photos = "Photos",
    calendar = "Calendar",
    notifications = "Notifications",
    notificationAllowlistTitle = "Allowed notification apps",
    notificationAllowlistHint = "Enter Android package names separated by commas or new lines. An empty list blocks every notification.",
    saveNotificationAllowlist = "Save allowed apps",
    notificationAllowlistInvalid = "Use valid Android package names only.",
    appUsage = "App usage",
    off = "Off",
    selected = "Connected",
    indexed = "Indexed",
    notImplemented = "Not implemented",
    connectorConnectionUnavailable = "Source connection is unavailable until this connector is implemented.",
    highSensitivity = "High sensitivity",
    veryHighSensitivity = "Very high sensitivity",
    noDerivedEvents = "No derived memory events indexed.",
    noPlaceClusters = "No place clusters indexed.",
    loadingLocalState = "Loading local state.",
    noAnswerAvailable = "No answer available from indexed evidence.",
    cannotAnswerFromIndexedEvidence = "I cannot answer from indexed, cited evidence.",
    noCitedEvidence = "No cited evidence available.",
    addAndIndexLocalFileFirst = "Add and index a local text or Markdown file first.",
    tryIndexingAgain = "Try indexing local evidence again.",
    noMissingSources = "No missing sources for selected evidence.",
    enterMemoryQuestion = "Enter a memory question.",
    noLocalTextEvidenceIndexed = "No local text or Markdown evidence has been indexed.",
    askFromIndexedEvidence = "Ask from indexed evidence after adding and indexing a local file.",
    localFileSelectionFailed = "Local file selection failed.",
    revokeFailed = "Revoke failed.",
    deleteFailed = "Delete failed.",
    indexingFailed = "Indexing failed.",
    searchFailed = "Search failed.",
    selectedLocalFile = "Connected local file. Run Index now to update evidence.",
    unsupportedFileOrPermissionDenied = "Unsupported file or read permission was not granted.",
    sourcePermissionDenied = "Source permission was not granted.",
    noLocalFilesIndexed = "No local files indexed.",
    noSourcesReadyToIndex = "No connected sources are ready to index.",
    revokedLocalFiles = "Revoked local file access and deleted derived local file data.",
    networkPermissionRestricted = "Network: typed external enrichment and fixed-catalog model downloads only",
    accountAbsent = "Account: absent",
    cloudSyncAbsent = "Cloud sync: absent",
    telemetryAbsent = "Telemetry: absent",
    crashAnalyticsAbsent = "Crash analytics: absent",
    encryptedStoreSqlCipher = "Encrypted store: SQLCipher",
    localGemmaModelTitle = "Local Gemma model",
    localGemmaModelReady = "Local Gemma status: ready",
    localGemmaModelLoading = "Local Gemma status: loading",
    localGemmaModelUnavailable = "Local Gemma status: unavailable; Ask uses template fallback.",
    localGemmaModelApkNotBundled = "Model weights are not bundled in the APK; import or download an approved model at runtime.",
    localGemmaModelDownloadGuide = "Get model from Google AI Edge LiteRT-LM Gemma docs or Hugging Face repo: litert-community/gemma-4-E2B-it-litert-lm.",
    localGemmaModelDownloadUrl = "Download page: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "Expected file name: gemma-4-E2B-it.litertlm.",
    localGemmaModelDevInstallGuide = "Current build install path: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/.",
    openLocalGemmaModelDownloadPage = "Open model download page",
    importLocalGemmaModel = "Import local Gemma model",
    deleteLocalGemmaModel = "Delete imported Gemma model",
    localGemmaModelImported = "Imported local Gemma model. Ask will use it when evidence is available.",
    localGemmaModelDeleted = "Deleted imported local Gemma model.",
    localGemmaModelDeleteMissing = "No imported local Gemma model found.",
    localGemmaModelImportFailed = "Local Gemma model import failed.",
    localGemmaModelDownloadOpenFailed = "Could not open model download page.",
    localGemmaModelInvalidFile = "Select a .litertlm model file larger than 1 MB.",
    localModelCatalogTitle = "Local model",
    localModelSelectedBadge = "Selected",
    localModelRecommended = "Recommended for Grayin",
    localModelSelect = "Select",
    localModelDownload = "Download",
    localModelCancelDownload = "Cancel download",
    localModelDeleteDownloaded = "Delete downloaded model",
    localModelOpenDownloadPage = "Open page",
    localModelDownloadUnavailable = "Download is not configured yet.",
    localModelRequiresUnmeteredNetwork = "Downloads run only on Wi-Fi or unmetered network.",
    localModelUnknown = "Unknown local model.",
    indexedSourceReferencesPrefix = "Indexed source references:",
    derivedMemoryEventsPrefix = "Derived memory events:",
)

private val KoreanStrings = EnglishStrings.copy(
    languageCode = GrayinLanguage.KOREAN,
    ask = "질문",
    timeline = "타임라인",
    places = "장소",
    sources = "소스",
    settings = "설정",
    memoryQuestion = "기억 질문",
    search = "검색",
    searching = "검색 중",
    answer = "답변",
    confidencePrefix = "신뢰도:",
    evidence = "근거",
    show = "보기",
    hide = "숨기기",
    missingData = "부족한 데이터",
    sourceConnectionTitle = "질문 전 소스를 연결하세요",
    sourceConnectionBody = "Grayin은 여기서 사용자가 연결한 소스만 읽고 분석할 수 있습니다. 소스를 선택한 뒤 인덱싱을 실행해야 질문에서 파생 근거를 사용할 수 있습니다.",
    sourceConnectionPrivacyNote = "인덱싱은 원본을 일시적으로만 읽고 파생 기억, 인용, 소스 참조만 저장합니다.",
    invokeSource = "소스 연결",
    addLocalFile = "로컬 파일 추가",
    revoke = "권한 해제",
    delete = "삭제",
    indexNow = "지금 인덱싱",
    indexAllNow = "지금 모두 인덱싱",
    indexing = "인덱싱 중",
    automaticIndexing = "자동 인덱싱",
    automaticIndexingSettings = "자동 인덱싱 세부 설정",
    automaticIndexingOn = "켜짐",
    automaticIndexingOff = "꺼짐",
    chargingOnly = "충전 중일 때만",
    startHour = "시작 시간",
    endHour = "종료 시간",
    language = "언어",
    localFiles = "로컬 파일",
    location = "위치",
    photos = "사진",
    calendar = "캘린더",
    notifications = "알림",
    notificationAllowlistTitle = "허용할 알림 앱",
    notificationAllowlistHint = "Android 패키지 이름을 쉼표 또는 줄바꿈으로 구분해 입력하세요. 비워 두면 모든 알림을 차단합니다.",
    saveNotificationAllowlist = "허용 앱 저장",
    notificationAllowlistInvalid = "올바른 Android 패키지 이름만 입력하세요.",
    appUsage = "앱 사용",
    off = "꺼짐",
    selected = "연결됨",
    indexed = "인덱싱됨",
    notImplemented = "미구현",
    connectorConnectionUnavailable = "이 커넥터가 구현될 때까지 소스 연결을 사용할 수 없습니다.",
    highSensitivity = "높은 민감도",
    veryHighSensitivity = "매우 높은 민감도",
    noDerivedEvents = "인덱싱된 파생 기억 이벤트가 없습니다.",
    noPlaceClusters = "인덱싱된 장소 클러스터가 없습니다.",
    loadingLocalState = "로컬 상태를 불러오는 중입니다.",
    noAnswerAvailable = "인덱싱된 근거에서 나온 답변이 없습니다.",
    cannotAnswerFromIndexedEvidence = "인덱싱되고 인용된 근거만으로는 답할 수 없습니다.",
    noCitedEvidence = "인용된 근거가 없습니다.",
    addAndIndexLocalFileFirst = "먼저 로컬 텍스트 또는 Markdown 파일을 추가하고 인덱싱하세요.",
    tryIndexingAgain = "로컬 근거를 다시 인덱싱해 보세요.",
    noMissingSources = "선택된 근거에 부족한 소스가 없습니다.",
    enterMemoryQuestion = "기억 질문을 입력하세요.",
    noLocalTextEvidenceIndexed = "인덱싱된 로컬 텍스트 또는 Markdown 근거가 없습니다.",
    askFromIndexedEvidence = "로컬 파일을 추가하고 인덱싱한 뒤 근거에서 질문하세요.",
    localFileSelectionFailed = "로컬 파일 선택에 실패했습니다.",
    revokeFailed = "권한 해제에 실패했습니다.",
    deleteFailed = "삭제에 실패했습니다.",
    indexingFailed = "인덱싱에 실패했습니다.",
    searchFailed = "검색에 실패했습니다.",
    selectedLocalFile = "로컬 파일을 연결했습니다. 근거를 업데이트하려면 지금 인덱싱을 실행하세요.",
    unsupportedFileOrPermissionDenied = "지원하지 않는 파일이거나 읽기 권한이 허용되지 않았습니다.",
    sourcePermissionDenied = "소스 권한이 허용되지 않았습니다.",
    noLocalFilesIndexed = "인덱싱된 로컬 파일이 없습니다.",
    noSourcesReadyToIndex = "인덱싱할 수 있는 연결된 소스가 없습니다.",
    revokedLocalFiles = "로컬 파일 접근 권한을 해제하고 파생 로컬 파일 데이터를 삭제했습니다.",
    networkPermissionRestricted = "네트워크: typed 외부 enrichment 및 고정 catalog 모델 다운로드만 허용",
    accountAbsent = "계정: 없음",
    cloudSyncAbsent = "클라우드 동기화: 없음",
    telemetryAbsent = "텔레메트리: 없음",
    crashAnalyticsAbsent = "크래시 분석: 없음",
    encryptedStoreSqlCipher = "암호화 저장소: SQLCipher",
    localGemmaModelTitle = "로컬 Gemma 모델",
    localGemmaModelReady = "로컬 Gemma 상태: 준비됨",
    localGemmaModelLoading = "로컬 Gemma 상태: 로딩 중",
    localGemmaModelUnavailable = "로컬 Gemma 상태: 없음. 질문은 템플릿 fallback을 사용합니다.",
    localGemmaModelApkNotBundled = "모델 weight는 APK에 포함되지 않습니다. 승인된 모델을 실행 시 가져오거나 다운로드하세요.",
    localGemmaModelDownloadGuide = "모델 획득: Google AI Edge LiteRT-LM Gemma 문서 또는 Hugging Face repo litert-community/gemma-4-E2B-it-litert-lm.",
    localGemmaModelDownloadUrl = "다운로드 페이지: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "필요 파일명: gemma-4-E2B-it.litertlm.",
    localGemmaModelDevInstallGuide = "현재 빌드 설치 경로: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/.",
    openLocalGemmaModelDownloadPage = "모델 다운로드 페이지 열기",
    importLocalGemmaModel = "로컬 Gemma 모델 가져오기",
    deleteLocalGemmaModel = "가져온 Gemma 모델 삭제",
    localGemmaModelImported = "로컬 Gemma 모델을 가져왔습니다. 근거가 있으면 질문에서 사용합니다.",
    localGemmaModelDeleted = "가져온 로컬 Gemma 모델을 삭제했습니다.",
    localGemmaModelDeleteMissing = "가져온 로컬 Gemma 모델이 없습니다.",
    localGemmaModelImportFailed = "로컬 Gemma 모델 가져오기에 실패했습니다.",
    localGemmaModelDownloadOpenFailed = "모델 다운로드 페이지를 열 수 없습니다.",
    localGemmaModelInvalidFile = "1MB보다 큰 .litertlm 모델 파일을 선택하세요.",
    localModelCatalogTitle = "로컬 모델",
    localModelSelectedBadge = "선택됨",
    localModelRecommended = "Grayin 권장 모델",
    localModelSelect = "선택",
    localModelDownload = "다운로드",
    localModelCancelDownload = "다운로드 취소",
    localModelDeleteDownloaded = "다운로드된 모델 삭제",
    localModelOpenDownloadPage = "페이지 열기",
    localModelDownloadUnavailable = "아직 다운로드 설정이 없습니다.",
    localModelRequiresUnmeteredNetwork = "다운로드는 Wi-Fi 또는 무제한 네트워크에서만 실행됩니다.",
    localModelUnknown = "알 수 없는 로컬 모델입니다.",
    indexedSourceReferencesPrefix = "인덱싱된 소스 참조:",
    derivedMemoryEventsPrefix = "파생 기억 이벤트:",
)

private val JapaneseStrings = EnglishStrings.copy(
    languageCode = GrayinLanguage.JAPANESE,
    ask = "質問",
    timeline = "タイムライン",
    places = "場所",
    sources = "ソース",
    settings = "設定",
    memoryQuestion = "記憶の質問",
    search = "検索",
    searching = "検索中",
    answer = "回答",
    confidencePrefix = "信頼度:",
    evidence = "根拠",
    show = "表示",
    hide = "非表示",
    missingData = "不足データ",
    sourceConnectionTitle = "質問前にソースを接続",
    sourceConnectionBody = "Grayinはここで接続したソースだけを読み取り、分析できます。ソースを選択してからインデックスすると、質問で派生根拠を使えます。",
    sourceConnectionPrivacyNote = "インデックスは原本を一時的にだけ読み取り、派生記憶、引用、ソース参照だけを保存します。",
    invokeSource = "ソースを接続",
    addLocalFile = "ローカルファイルを追加",
    revoke = "許可を解除",
    delete = "削除",
    indexNow = "今すぐインデックス",
    indexAllNow = "今すぐすべてインデックス",
    indexing = "インデックス中",
    automaticIndexing = "自動インデックス",
    automaticIndexingSettings = "自動インデックス詳細設定",
    automaticIndexingOn = "オン",
    automaticIndexingOff = "オフ",
    chargingOnly = "充電中のみ",
    startHour = "開始時間",
    endHour = "終了時間",
    language = "言語",
    localFiles = "ローカルファイル",
    location = "位置",
    photos = "写真",
    calendar = "カレンダー",
    notifications = "通知",
    notificationAllowlistTitle = "許可する通知アプリ",
    notificationAllowlistHint = "Androidパッケージ名をカンマまたは改行で区切って入力してください。空欄の場合はすべての通知を遮断します。",
    saveNotificationAllowlist = "許可アプリを保存",
    notificationAllowlistInvalid = "有効なAndroidパッケージ名だけを入力してください。",
    appUsage = "アプリ使用",
    off = "オフ",
    selected = "接続済み",
    indexed = "インデックス済み",
    notImplemented = "未実装",
    connectorConnectionUnavailable = "このコネクタが実装されるまで、ソース接続は利用できません。",
    highSensitivity = "高い機密性",
    veryHighSensitivity = "非常に高い機密性",
    noDerivedEvents = "インデックス済みの派生記憶イベントはありません。",
    noPlaceClusters = "インデックス済みの場所クラスタはありません。",
    loadingLocalState = "ローカル状態を読み込み中です。",
    noAnswerAvailable = "インデックス済みの根拠から得られる回答はありません。",
    cannotAnswerFromIndexedEvidence = "インデックス済みで引用可能な根拠だけでは回答できません。",
    noCitedEvidence = "引用済みの根拠はありません。",
    addAndIndexLocalFileFirst = "まずローカルのテキストまたはMarkdownファイルを追加してインデックスしてください。",
    tryIndexingAgain = "ローカル根拠をもう一度インデックスしてください。",
    noMissingSources = "選択された根拠に不足しているソースはありません。",
    enterMemoryQuestion = "記憶の質問を入力してください。",
    noLocalTextEvidenceIndexed = "ローカルのテキストまたはMarkdown根拠はインデックスされていません。",
    askFromIndexedEvidence = "ローカルファイルを追加してインデックスした後、根拠に基づいて質問してください。",
    localFileSelectionFailed = "ローカルファイルの選択に失敗しました。",
    revokeFailed = "許可の解除に失敗しました。",
    deleteFailed = "削除に失敗しました。",
    indexingFailed = "インデックスに失敗しました。",
    searchFailed = "検索に失敗しました。",
    selectedLocalFile = "ローカルファイルを接続しました。根拠を更新するには今すぐインデックスしてください。",
    unsupportedFileOrPermissionDenied = "未対応のファイル、または読み取り許可がありません。",
    sourcePermissionDenied = "ソース権限が許可されていません。",
    noLocalFilesIndexed = "インデックス済みのローカルファイルはありません。",
    noSourcesReadyToIndex = "インデックス可能な接続済みソースがありません。",
    revokedLocalFiles = "ローカルファイルへのアクセス許可を解除し、派生ローカルファイルデータを削除しました。",
    networkPermissionRestricted = "ネットワーク: typed外部enrichmentと固定catalogモデルのダウンロードのみ許可",
    accountAbsent = "アカウント: なし",
    cloudSyncAbsent = "クラウド同期: なし",
    telemetryAbsent = "テレメトリー: なし",
    crashAnalyticsAbsent = "クラッシュ分析: なし",
    encryptedStoreSqlCipher = "暗号化ストア: SQLCipher",
    localGemmaModelTitle = "ローカルGemmaモデル",
    localGemmaModelReady = "ローカルGemma状態: 準備完了",
    localGemmaModelLoading = "ローカルGemma状態: 読み込み中",
    localGemmaModelUnavailable = "ローカルGemma状態: 利用不可。質問はテンプレートfallbackを使います。",
    localGemmaModelApkNotBundled = "モデルweightはAPKに含まれません。承認済みモデルを実行時にインポートまたはダウンロードしてください。",
    localGemmaModelDownloadGuide = "モデル入手先: Google AI Edge LiteRT-LM Gemma docs または Hugging Face repo litert-community/gemma-4-E2B-it-litert-lm。",
    localGemmaModelDownloadUrl = "ダウンロードページ: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "必要なファイル名: gemma-4-E2B-it.litertlm。",
    localGemmaModelDevInstallGuide = "現在のビルドの設置先: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/。",
    openLocalGemmaModelDownloadPage = "モデルダウンロードページを開く",
    importLocalGemmaModel = "ローカルGemmaモデルをインポート",
    deleteLocalGemmaModel = "インポート済みGemmaモデルを削除",
    localGemmaModelImported = "ローカルGemmaモデルをインポートしました。根拠がある場合、質問で使います。",
    localGemmaModelDeleted = "インポート済みローカルGemmaモデルを削除しました。",
    localGemmaModelDeleteMissing = "インポート済みローカルGemmaモデルはありません。",
    localGemmaModelImportFailed = "ローカルGemmaモデルのインポートに失敗しました。",
    localGemmaModelDownloadOpenFailed = "モデルダウンロードページを開けません。",
    localGemmaModelInvalidFile = "1MBを超える .litertlm モデルファイルを選択してください。",
    localModelCatalogTitle = "ローカルモデル",
    localModelSelectedBadge = "選択中",
    localModelRecommended = "Grayin推奨モデル",
    localModelSelect = "選択",
    localModelDownload = "ダウンロード",
    localModelCancelDownload = "ダウンロードをキャンセル",
    localModelDeleteDownloaded = "ダウンロード済みモデルを削除",
    localModelOpenDownloadPage = "ページを開く",
    localModelDownloadUnavailable = "ダウンロード設定はまだありません。",
    localModelRequiresUnmeteredNetwork = "ダウンロードはWi-Fiまたは従量制でないネットワークでのみ実行します。",
    localModelUnknown = "不明なローカルモデルです。",
    indexedSourceReferencesPrefix = "インデックス済みソース参照:",
    derivedMemoryEventsPrefix = "派生記憶イベント:",
)

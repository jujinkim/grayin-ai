package ai.grayin.app

import android.content.Context
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
    val sourceInvocationTitle: String,
    val sourceInvocationBody: String,
    val sourceInvocationPrivacyNote: String,
    val addLocalFile: String,
    val revoke: String,
    val delete: String,
    val indexNow: String,
    val indexing: String,
    val language: String,
    val localFiles: String,
    val location: String,
    val photos: String,
    val calendar: String,
    val notifications: String,
    val appUsage: String,
    val off: String,
    val selected: String,
    val indexed: String,
    val notImplemented: String,
    val connectorInvocationUnavailable: String,
    val localFilesOffDescription: String,
    val localFilesSelectedDescription: String,
    val localFilesIndexedDescription: String,
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
    val noLocalFilesIndexed: String,
    val revokedLocalFiles: String,
    val networkPermissionRestricted: String,
    val accountAbsent: String,
    val cloudSyncAbsent: String,
    val telemetryAbsent: String,
    val crashAnalyticsAbsent: String,
    val encryptedStoreSqlCipher: String,
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
    sourceInvocationTitle = "Invoke sources before asking",
    sourceInvocationBody = "Grayin can read and analyze only sources you invoke here. Select a source, then run indexing so Ask can use derived evidence.",
    sourceInvocationPrivacyNote = "Indexing reads originals transiently and stores derived memory, citations, and source references only.",
    addLocalFile = "Add local file",
    revoke = "Revoke",
    delete = "Delete",
    indexNow = "Index now",
    indexing = "Indexing",
    language = "Language",
    localFiles = "Local files",
    location = "Location",
    photos = "Photos",
    calendar = "Calendar",
    notifications = "Notifications",
    appUsage = "App usage",
    off = "Off",
    selected = "Selected",
    indexed = "Indexed",
    notImplemented = "Not implemented",
    connectorInvocationUnavailable = "Source invocation is unavailable until this connector is implemented.",
    localFilesOffDescription = "Invoke local Text or Markdown files by selecting them here.",
    localFilesSelectedDescription = "Source selected. Run indexing so Grayin can analyze it for Ask.",
    localFilesIndexedDescription = "Indexed derived evidence is available for Ask. Revoke access or delete derived data anytime.",
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
    selectedLocalFile = "Selected local file. Run Index now to update evidence.",
    unsupportedFileOrPermissionDenied = "Unsupported file or read permission was not granted.",
    noLocalFilesIndexed = "No local files indexed.",
    revokedLocalFiles = "Revoked local file access and deleted derived local file data.",
    networkPermissionRestricted = "Network permission: restricted to typed enrichment methods",
    accountAbsent = "Account: absent",
    cloudSyncAbsent = "Cloud sync: absent",
    telemetryAbsent = "Telemetry: absent",
    crashAnalyticsAbsent = "Crash analytics: absent",
    encryptedStoreSqlCipher = "Encrypted store: SQLCipher",
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
    sourceInvocationTitle = "질문 전 소스를 호출하세요",
    sourceInvocationBody = "Grayin은 여기서 사용자가 호출한 소스만 읽고 분석할 수 있습니다. 소스를 선택한 뒤 인덱싱을 실행해야 질문에서 파생 근거를 사용할 수 있습니다.",
    sourceInvocationPrivacyNote = "인덱싱은 원본을 일시적으로만 읽고 파생 기억, 인용, 소스 참조만 저장합니다.",
    addLocalFile = "로컬 파일 추가",
    revoke = "권한 해제",
    delete = "삭제",
    indexNow = "지금 인덱싱",
    indexing = "인덱싱 중",
    language = "언어",
    localFiles = "로컬 파일",
    location = "위치",
    photos = "사진",
    calendar = "캘린더",
    notifications = "알림",
    appUsage = "앱 사용",
    off = "꺼짐",
    selected = "선택됨",
    indexed = "인덱싱됨",
    notImplemented = "미구현",
    connectorInvocationUnavailable = "이 커넥터가 구현될 때까지 소스 호출을 사용할 수 없습니다.",
    localFilesOffDescription = "로컬 Text 또는 Markdown 파일을 선택해 소스를 호출하세요.",
    localFilesSelectedDescription = "소스가 선택되었습니다. Grayin이 분석할 수 있게 인덱싱을 실행하세요.",
    localFilesIndexedDescription = "질문에서 사용할 파생 근거가 인덱싱되었습니다. 언제든 접근 권한 해제 또는 파생 데이터 삭제가 가능합니다.",
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
    selectedLocalFile = "로컬 파일을 선택했습니다. 근거를 업데이트하려면 지금 인덱싱을 실행하세요.",
    unsupportedFileOrPermissionDenied = "지원하지 않는 파일이거나 읽기 권한이 허용되지 않았습니다.",
    noLocalFilesIndexed = "인덱싱된 로컬 파일이 없습니다.",
    revokedLocalFiles = "로컬 파일 접근 권한을 해제하고 파생 로컬 파일 데이터를 삭제했습니다.",
    networkPermissionRestricted = "네트워크 권한: typed enrichment method로 제한됨",
    accountAbsent = "계정: 없음",
    cloudSyncAbsent = "클라우드 동기화: 없음",
    telemetryAbsent = "텔레메트리: 없음",
    crashAnalyticsAbsent = "크래시 분석: 없음",
    encryptedStoreSqlCipher = "암호화 저장소: SQLCipher",
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
    sourceInvocationTitle = "質問前にソースを呼び出す",
    sourceInvocationBody = "Grayinはここで呼び出したソースだけを読み取り、分析できます。ソースを選択してからインデックスすると、質問で派生根拠を使えます。",
    sourceInvocationPrivacyNote = "インデックスは原本を一時的にだけ読み取り、派生記憶、引用、ソース参照だけを保存します。",
    addLocalFile = "ローカルファイルを追加",
    revoke = "許可を解除",
    delete = "削除",
    indexNow = "今すぐインデックス",
    indexing = "インデックス中",
    language = "言語",
    localFiles = "ローカルファイル",
    location = "位置",
    photos = "写真",
    calendar = "カレンダー",
    notifications = "通知",
    appUsage = "アプリ使用",
    off = "オフ",
    selected = "選択済み",
    indexed = "インデックス済み",
    notImplemented = "未実装",
    connectorInvocationUnavailable = "このコネクタが実装されるまで、ソース呼び出しは利用できません。",
    localFilesOffDescription = "ローカルのTextまたはMarkdownファイルを選択してソースを呼び出します。",
    localFilesSelectedDescription = "ソースが選択されています。Grayinが分析できるようにインデックスしてください。",
    localFilesIndexedDescription = "質問で使える派生根拠がインデックス済みです。アクセス許可の解除や派生データ削除はいつでもできます。",
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
    selectedLocalFile = "ローカルファイルを選択しました。根拠を更新するには今すぐインデックスしてください。",
    unsupportedFileOrPermissionDenied = "未対応のファイル、または読み取り許可がありません。",
    noLocalFilesIndexed = "インデックス済みのローカルファイルはありません。",
    revokedLocalFiles = "ローカルファイルへのアクセス許可を解除し、派生ローカルファイルデータを削除しました。",
    networkPermissionRestricted = "ネットワーク権限: typed enrichment method に制限",
    accountAbsent = "アカウント: なし",
    cloudSyncAbsent = "クラウド同期: なし",
    telemetryAbsent = "テレメトリー: なし",
    crashAnalyticsAbsent = "クラッシュ分析: なし",
    encryptedStoreSqlCipher = "暗号化ストア: SQLCipher",
    indexedSourceReferencesPrefix = "インデックス済みソース参照:",
    derivedMemoryEventsPrefix = "派生記憶イベント:",
)

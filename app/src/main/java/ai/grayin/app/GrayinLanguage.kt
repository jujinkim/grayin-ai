package ai.grayin.app

import android.content.Context
import ai.grayin.core.ai.ModelDownloadStatus
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.ocr.OcrLanguagePack
import ai.grayin.core.ocr.OcrLanguagePackFailureCode
import ai.grayin.core.ocr.OcrLanguagePackStatus
import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTrigger
import ai.grayin.core.security.AppLockState
import ai.grayin.core.security.AppSecurityFailure
import ai.grayin.core.transfer.TransferFailureCode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val indexingStatusTitle: String,
    val recentIndexingTasks: String,
    val noRecentIndexingTasks: String,
    val invalidAutomaticIndexingWindow: String,
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
    val reconnectionRequired: String,
    val connectorConnectionUnavailable: String,
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
    val onlineEnrichmentTitle: String,
    val onlineEnrichmentDisclosure: String,
    val onlineEnrichmentProviderCredit: String,
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
    fun appSecurityTitle(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "앱 보안"
        GrayinLanguage.JAPANESE -> "アプリのセキュリティ"
        GrayinLanguage.ENGLISH -> "App security"
    }

    fun appSecurityDisclosure(): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "스크린샷 차단과 시스템 생체 인증 또는 기기 잠금 인증으로 화면을 보호합니다. 앱 잠금이 켜져 있으면 스크린샷도 항상 차단됩니다. Grayin은 생체 정보, PIN, 패턴, 비밀번호를 저장하지 않습니다."
        GrayinLanguage.JAPANESE ->
            "スクリーンショットのブロックと、システムの生体認証または端末の画面ロック認証で画面を保護します。アプリロックがオンの間はスクリーンショットも常にブロックされます。Grayinは生体情報、PIN、パターン、パスワードを保存しません。"
        GrayinLanguage.ENGLISH ->
            "Protect the screen with screenshot blocking and system biometric or device-lock authentication. App lock always blocks screenshots while enabled. Grayin does not store biometrics, PINs, patterns, or passwords."
    }

    fun screenshotBlocking(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "스크린샷 차단"
        GrayinLanguage.JAPANESE -> "スクリーンショットをブロック"
        GrayinLanguage.ENGLISH -> "Block screenshots"
    }

    fun appLock(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "앱 잠금"
        GrayinLanguage.JAPANESE -> "アプリロック"
        GrayinLanguage.ENGLISH -> "App lock"
    }

    fun appLockScreenTitle(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "Grayin 잠금"
        GrayinLanguage.JAPANESE -> "Grayinはロックされています"
        GrayinLanguage.ENGLISH -> "Grayin is locked"
    }

    fun appLockScreenBody(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "기기의 생체 인증, PIN, 패턴 또는 비밀번호로 잠금을 해제하세요."
        GrayinLanguage.JAPANESE -> "端末の生体認証、PIN、パターン、またはパスワードでロックを解除してください。"
        GrayinLanguage.ENGLISH -> "Unlock with your device biometrics, PIN, pattern, or password."
    }

    fun unlockApp(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "잠금 해제"
        GrayinLanguage.JAPANESE -> "ロック解除"
        GrayinLanguage.ENGLISH -> "Unlock"
    }

    fun retryAuthentication(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "다시 인증"
        GrayinLanguage.JAPANESE -> "認証を再試行"
        GrayinLanguage.ENGLISH -> "Try authentication again"
    }

    fun useDeviceCredential(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "기기 PIN, 패턴 또는 비밀번호 사용"
        GrayinLanguage.JAPANESE -> "端末のPIN、パターン、またはパスワードを使用"
        GrayinLanguage.ENGLISH -> "Use device PIN, pattern, or password"
    }

    fun openDeviceSecuritySettings(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "기기 보안 설정 열기"
        GrayinLanguage.JAPANESE -> "端末のセキュリティ設定を開く"
        GrayinLanguage.ENGLISH -> "Open device security settings"
    }

    fun appSecurityFailure(failure: AppSecurityFailure): String = when (languageCode) {
        GrayinLanguage.KOREAN -> when (failure) {
            AppSecurityFailure.AUTHENTICATION_FAILED -> "인증이 일치하지 않습니다. 다시 시도하세요."
            AppSecurityFailure.AUTHENTICATION_CANCELLED -> "인증을 취소했습니다. 보안 설정은 변경되지 않았습니다."
            AppSecurityFailure.AUTHENTICATION_LOCKED_OUT -> "인증 시도가 잠겼습니다. 기기 잠금 인증을 사용하거나 잠시 후 다시 시도하세요."
            AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED -> "기기에 생체 인증 또는 화면 잠금이 설정되어 있지 않습니다."
            AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE -> "기기 인증을 지금 사용할 수 없습니다. 잠시 후 다시 시도하세요."
            AppSecurityFailure.AUTHENTICATION_UNSUPPORTED -> "이 기기는 지원되는 시스템 인증을 제공하지 않습니다."
            AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED -> "기기 인증을 사용하려면 보안 업데이트가 필요합니다."
            AppSecurityFailure.PREFERENCE_WRITE_FAILED -> "앱 보안 설정을 안전하게 저장하지 못했습니다. 기존 설정을 유지합니다."
        }

        GrayinLanguage.JAPANESE -> when (failure) {
            AppSecurityFailure.AUTHENTICATION_FAILED -> "認証が一致しません。もう一度お試しください。"
            AppSecurityFailure.AUTHENTICATION_CANCELLED -> "認証をキャンセルしました。セキュリティ設定は変更されていません。"
            AppSecurityFailure.AUTHENTICATION_LOCKED_OUT -> "認証がロックされています。端末の画面ロック認証を使うか、しばらくしてから再試行してください。"
            AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED -> "端末に生体認証または画面ロックが設定されていません。"
            AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE -> "端末認証は現在利用できません。しばらくしてから再試行してください。"
            AppSecurityFailure.AUTHENTICATION_UNSUPPORTED -> "この端末には対応するシステム認証がありません。"
            AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED -> "端末認証を使用するにはセキュリティ更新が必要です。"
            AppSecurityFailure.PREFERENCE_WRITE_FAILED -> "アプリのセキュリティ設定を安全に保存できませんでした。以前の設定を維持します。"
        }

        GrayinLanguage.ENGLISH -> when (failure) {
            AppSecurityFailure.AUTHENTICATION_FAILED -> "Authentication did not match. Try again."
            AppSecurityFailure.AUTHENTICATION_CANCELLED -> "Authentication canceled. No security setting was changed."
            AppSecurityFailure.AUTHENTICATION_LOCKED_OUT -> "Authentication is locked out. Use your device credential or try again later."
            AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED -> "No biometric or device screen lock is configured."
            AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE -> "Device authentication is temporarily unavailable. Try again later."
            AppSecurityFailure.AUTHENTICATION_UNSUPPORTED -> "This device has no supported system authentication."
            AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED -> "A security update is required before device authentication can be used."
            AppSecurityFailure.PREFERENCE_WRITE_FAILED -> "Could not save the app-security setting safely. The previous setting is unchanged."
        }
    }

    fun appLockStatus(state: AppLockState): String = when (languageCode) {
        GrayinLanguage.KOREAN -> when (state) {
            AppLockState.DISABLED -> "앱 잠금 꺼짐"
            AppLockState.LOCKED -> "앱 잠김"
            AppLockState.AUTHENTICATING -> "기기에서 인증 중"
            AppLockState.UNLOCKED -> "앱 잠금 해제됨"
        }

        GrayinLanguage.JAPANESE -> when (state) {
            AppLockState.DISABLED -> "アプリロック: オフ"
            AppLockState.LOCKED -> "アプリはロック中"
            AppLockState.AUTHENTICATING -> "端末で認証中"
            AppLockState.UNLOCKED -> "アプリのロックを解除済み"
        }

        GrayinLanguage.ENGLISH -> when (state) {
            AppLockState.DISABLED -> "App lock off"
            AppLockState.LOCKED -> "App locked"
            AppLockState.AUTHENTICATING -> "Authenticating on device"
            AppLockState.UNLOCKED -> "App unlocked"
        }
    }

    fun screenshotBlockingSaved(enabled: Boolean): String = when (languageCode) {
        GrayinLanguage.KOREAN -> if (enabled) "스크린샷 차단을 켰습니다." else "스크린샷 차단을 껐습니다."
        GrayinLanguage.JAPANESE -> if (enabled) "スクリーンショットのブロックをオンにしました。" else "スクリーンショットのブロックをオフにしました。"
        GrayinLanguage.ENGLISH -> if (enabled) "Screenshot blocking enabled." else "Screenshot blocking disabled."
    }

    fun appLockSaved(enabled: Boolean): String = when (languageCode) {
        GrayinLanguage.KOREAN -> if (enabled) "앱 잠금을 켰습니다." else "앱 잠금을 껐습니다."
        GrayinLanguage.JAPANESE -> if (enabled) "アプリロックをオンにしました。" else "アプリロックをオフにしました。"
        GrayinLanguage.ENGLISH -> if (enabled) "App lock enabled." else "App lock disabled."
    }

    fun backupTitle(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "암호화 백업"
        GrayinLanguage.JAPANESE -> "暗号化バックアップ"
        GrayinLanguage.ENGLISH -> "Encrypted backup"
    }

    fun backupDisclosure(): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "파생 메모리만 비밀번호로 암호화합니다. 원본·권한·설정은 포함하지 않으며 로컬 문서 공급자만 사용합니다."
        GrayinLanguage.JAPANESE ->
            "派生メモリのみをパスワードで暗号化します。原本・権限・設定は含めず、ローカル文書プロバイダーのみを使用します。"
        GrayinLanguage.ENGLISH ->
            "Password-encrypts derived memory only. Originals, permissions, and settings are excluded; only local document providers are used."
    }

    fun exportEncryptedBackup(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "암호화 백업 내보내기"
        GrayinLanguage.JAPANESE -> "暗号化バックアップを書き出す"
        GrayinLanguage.ENGLISH -> "Export encrypted backup"
    }

    fun importEncryptedBackup(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "암호화 백업 가져오기"
        GrayinLanguage.JAPANESE -> "暗号化バックアップを読み込む"
        GrayinLanguage.ENGLISH -> "Import encrypted backup"
    }

    fun backupExportPasswordBody(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "12~128자의 비밀번호를 입력하고 확인하세요. 분실한 비밀번호는 복구할 수 없습니다."
        GrayinLanguage.JAPANESE -> "12〜128文字のパスワードを入力して確認してください。紛失したパスワードは復元できません。"
        GrayinLanguage.ENGLISH -> "Enter and confirm a 12–128 character password. A lost password cannot be recovered."
    }

    fun backupImportPasswordBody(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "이 백업을 만들 때 사용한 비밀번호를 입력하세요."
        GrayinLanguage.JAPANESE -> "このバックアップの作成時に使用したパスワードを入力してください。"
        GrayinLanguage.ENGLISH -> "Enter the password used to create this backup."
    }

    fun backupPassword(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "비밀번호"
        GrayinLanguage.JAPANESE -> "パスワード"
        GrayinLanguage.ENGLISH -> "Password"
    }

    fun backupConfirmPassword(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "비밀번호 확인"
        GrayinLanguage.JAPANESE -> "パスワードの確認"
        GrayinLanguage.ENGLISH -> "Confirm password"
    }

    fun backupPasswordsDoNotMatch(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "비밀번호가 일치하지 않습니다."
        GrayinLanguage.JAPANESE -> "パスワードが一致しません。"
        GrayinLanguage.ENGLISH -> "Passwords do not match."
    }

    fun backupImportWarningTitle(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "현재 파생 메모리를 교체할까요?"
        GrayinLanguage.JAPANESE -> "現在の派生メモリを置き換えますか？"
        GrayinLanguage.ENGLISH -> "Replace current derived memory?"
    }

    fun backupImportWarningBody(): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "가져오기는 현재 파생 메모리를 전부 교체합니다. 원본·권한·설정·로컬 문서 연결은 복원하지 않습니다. 자동 인덱싱과 외부 enrichment가 꺼지며 모든 소스를 다시 연결해야 합니다."
        GrayinLanguage.JAPANESE ->
            "読み込みは現在の派生メモリをすべて置き換えます。原本・権限・設定・ローカル文書リンクは復元されません。自動インデックスと外部enrichmentはオフになり、すべてのソースを再接続する必要があります。"
        GrayinLanguage.ENGLISH ->
            "Import replaces all current derived memory. Originals, permissions, settings, and local document links are not restored. Automatic indexing and external enrichment are turned off, and every source must be reconnected."
    }

    fun backupCanceled(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "백업 작업을 취소했습니다."
        GrayinLanguage.JAPANESE -> "バックアップ操作をキャンセルしました。"
        GrayinLanguage.ENGLISH -> "Backup action canceled."
    }

    fun backupExportSucceeded(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "암호화 백업을 내보냈습니다."
        GrayinLanguage.JAPANESE -> "暗号化バックアップを書き出しました。"
        GrayinLanguage.ENGLISH -> "Encrypted backup exported."
    }

    fun backupImportSucceeded(eventCount: Int, connectorCount: Int): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "파생 이벤트 ${eventCount}개를 가져왔습니다. 소스 ${connectorCount}개를 다시 연결하세요."
        GrayinLanguage.JAPANESE ->
            "派生イベント${eventCount}件を読み込みました。${connectorCount}件のソースを再接続してください。"
        GrayinLanguage.ENGLISH ->
            "Imported $eventCount derived event(s). Reconnect $connectorCount source(s)."
    }

    fun backupFailure(code: TransferFailureCode): String = when (languageCode) {
        GrayinLanguage.KOREAN -> when (code) {
            TransferFailureCode.CANCELLED -> "백업 작업을 취소했습니다."
            TransferFailureCode.PASSWORD_POLICY_FAILED -> "비밀번호는 12~128자여야 합니다."
            TransferFailureCode.AUTHENTICATION_FAILED -> "비밀번호가 맞지 않거나 백업이 손상되었습니다."
            TransferFailureCode.INVALID_FORMAT,
            TransferFailureCode.INVALID_PAYLOAD,
            -> "올바른 Grayin 백업 파일이 아닙니다."
            TransferFailureCode.UNSUPPORTED_VERSION -> "지원하지 않는 백업 버전입니다."
            TransferFailureCode.TOO_LARGE -> "백업 파일이 허용 크기를 초과했습니다."
            TransferFailureCode.CONSENT_RESET_FAILED -> "소스 동의 초기화를 완료하지 못했습니다."
            TransferFailureCode.STORE_TRANSACTION_FAILED -> "암호화 저장소 가져오기를 완료하지 못했습니다."
            else -> "백업 파일을 처리하지 못했습니다."
        }

        GrayinLanguage.JAPANESE -> when (code) {
            TransferFailureCode.CANCELLED -> "バックアップ操作をキャンセルしました。"
            TransferFailureCode.PASSWORD_POLICY_FAILED -> "パスワードは12〜128文字にしてください。"
            TransferFailureCode.AUTHENTICATION_FAILED -> "パスワードが違うか、バックアップが破損しています。"
            TransferFailureCode.INVALID_FORMAT,
            TransferFailureCode.INVALID_PAYLOAD,
            -> "有効なGrayinバックアップではありません。"
            TransferFailureCode.UNSUPPORTED_VERSION -> "未対応のバックアップバージョンです。"
            TransferFailureCode.TOO_LARGE -> "バックアップが許容サイズを超えています。"
            TransferFailureCode.CONSENT_RESET_FAILED -> "ソース同意のリセットを完了できませんでした。"
            TransferFailureCode.STORE_TRANSACTION_FAILED -> "暗号化ストアへの読み込みを完了できませんでした。"
            else -> "バックアップファイルを処理できませんでした。"
        }

        GrayinLanguage.ENGLISH -> when (code) {
            TransferFailureCode.CANCELLED -> "Backup action canceled."
            TransferFailureCode.PASSWORD_POLICY_FAILED -> "Password must be 12–128 characters."
            TransferFailureCode.AUTHENTICATION_FAILED -> "The password is incorrect or the backup is damaged."
            TransferFailureCode.INVALID_FORMAT,
            TransferFailureCode.INVALID_PAYLOAD,
            -> "This is not a valid Grayin backup."
            TransferFailureCode.UNSUPPORTED_VERSION -> "This backup version is not supported."
            TransferFailureCode.TOO_LARGE -> "The backup exceeds the allowed size."
            TransferFailureCode.CONSENT_RESET_FAILED -> "Could not reset source consent."
            TransferFailureCode.STORE_TRANSACTION_FAILED -> "Could not complete the encrypted-store import."
            else -> "Could not process the backup file."
        }
    }

    fun confirm(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "확인"
        GrayinLanguage.JAPANESE -> "確認"
        GrayinLanguage.ENGLISH -> "Confirm"
    }

    fun continueAction(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "계속"
        GrayinLanguage.JAPANESE -> "続ける"
        GrayinLanguage.ENGLISH -> "Continue"
    }

    fun cancel(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "취소"
        GrayinLanguage.JAPANESE -> "キャンセル"
        GrayinLanguage.ENGLISH -> "Cancel"
    }

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

    fun connectorScanIssue(code: ConnectorScanIssueCode): String {
        if (languageCode == GrayinLanguage.ENGLISH) return code.defaultEnglish
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (code) {
                ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED -> "소스 권한이 허용되지 않았습니다."
                ConnectorScanIssueCode.SOURCE_NOT_INVOKED -> "소스가 아직 연결되지 않았습니다."
                ConnectorScanIssueCode.SOURCE_UNAVAILABLE -> "이번 인덱싱에서 소스를 사용할 수 없습니다."
                ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE -> "인덱싱한 기간에 일정이 없습니다."
                ConnectorScanIssueCode.CALENDAR_EVENT_LIMIT_REACHED ->
                    "일정 스캔이 파생 이벤트 한도에 도달하여 인덱싱 범위가 일부만 처리되었습니다."
                ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE -> "인덱싱한 기간에 사진이 없습니다."
                ConnectorScanIssueCode.PHOTO_METADATA_LIMIT_REACHED ->
                    "사진 스캔이 메타데이터 한도에 도달하여 인덱싱 범위가 일부만 처리되었습니다."
                ConnectorScanIssueCode.NO_APP_USAGE_IN_RANGE -> "인덱싱한 기간에 앱 사용 기록이 없습니다."
                ConnectorScanIssueCode.APP_USAGE_EVENT_HISTORY_LIMITED ->
                    "Android가 앱 사용 이벤트를 제한된 기간만 보관하므로 오래되었거나 기간 경계를 넘는 사용 기록은 일부 누락될 수 있습니다."
                ConnectorScanIssueCode.APP_USAGE_EVENT_LIMIT_REACHED ->
                    "앱 사용 스캔이 임시 이벤트 한도에 도달하여 불완전한 결과를 저장하지 않았습니다."
                ConnectorScanIssueCode.APP_USAGE_DERIVED_OUTPUT_LIMIT_REACHED ->
                    "앱 사용 스캔이 파생 세션 한도에 도달하여 인덱싱 범위가 일부만 처리되었습니다."
                ConnectorScanIssueCode.NO_LAST_KNOWN_LOCATION -> "사용 가능한 최근 위치가 없습니다."
                ConnectorScanIssueCode.NOTIFICATION_ALLOWLIST_EMPTY -> "알림 허용 목록에 앱을 하나 이상 추가하세요."
                ConnectorScanIssueCode.NOTIFICATION_HISTORY_UNAVAILABLE -> "허용한 앱의 새 알림만 도착할 때 인덱싱됩니다."
                ConnectorScanIssueCode.NO_LOCAL_DOCUMENTS_SELECTED -> "선택한 로컬 문서가 없습니다."
                ConnectorScanIssueCode.LOCAL_DOCUMENT_PERMISSION_REVOKED -> "선택한 문서의 읽기 권한이 취소되었습니다."
                ConnectorScanIssueCode.LOCAL_DOCUMENT_TYPE_UNSUPPORTED -> "지원하지 않는 문서 형식입니다."
                ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED -> "선택한 문서를 읽을 수 없습니다."
                ConnectorScanIssueCode.LOCAL_DOCUMENT_SELECTION_LIMIT_REACHED ->
                    "선택한 로컬 문서가 스캔 한도를 초과했습니다."
                ConnectorScanIssueCode.LOCAL_DOCUMENT_TEXT_LIMIT_REACHED ->
                    "로컬 텍스트 문서가 임시 텍스트 제한을 초과했습니다."
                ConnectorScanIssueCode.DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED ->
                    "로컬 문서 스캔이 파생 페이지 제한에 도달했습니다."
                ConnectorScanIssueCode.DOCUMENT_FILE_TOO_LARGE -> "문서가 크기 제한을 초과했습니다."
                ConnectorScanIssueCode.DOCUMENT_SIZE_UNKNOWN -> "문서 크기를 안전하게 확인할 수 없습니다."
                ConnectorScanIssueCode.DOCUMENT_NOT_SEEKABLE -> "문서가 안전한 임의 접근을 지원하지 않습니다."
                ConnectorScanIssueCode.PDF_PAGE_LIMIT_EXCEEDED -> "PDF가 페이지 제한을 초과했습니다."
                ConnectorScanIssueCode.PDF_PASSWORD_REQUIRED -> "PDF를 열려면 암호가 필요합니다."
                ConnectorScanIssueCode.PDF_MALFORMED -> "PDF가 손상되었거나 지원하지 않는 형식입니다."
                ConnectorScanIssueCode.PDF_PAGE_DIMENSIONS_UNSUPPORTED -> "지원하지 않는 크기의 PDF 페이지가 있습니다."
                ConnectorScanIssueCode.OCR_MODEL_UNAVAILABLE -> "필요한 기기 내 OCR 언어 데이터가 설치되지 않았습니다."
                ConnectorScanIssueCode.OCR_PAGE_LIMIT_REACHED -> "PDF가 문서별 OCR 페이지 제한을 초과했습니다."
                ConnectorScanIssueCode.OCR_TIMED_OUT -> "기기 내 OCR이 시간 제한을 초과했습니다."
                ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED -> "전용 문서 처리 프로세스가 예기치 않게 중단되었습니다."
                ConnectorScanIssueCode.DOCUMENT_PROCESS_TIMED_OUT -> "문서 처리가 시간 제한을 초과했습니다."
                ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT -> "문서에서 추출할 수 있는 텍스트를 찾지 못했습니다."
                ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX -> "문서 일부만 인덱싱했습니다."
            }

            GrayinLanguage.JAPANESE -> when (code) {
                ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED -> "ソースの権限が許可されていません。"
                ConnectorScanIssueCode.SOURCE_NOT_INVOKED -> "ソースはまだ接続されていません。"
                ConnectorScanIssueCode.SOURCE_UNAVAILABLE -> "今回のインデックスではソースを利用できません。"
                ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE -> "対象期間に予定がありません。"
                ConnectorScanIssueCode.CALENDAR_EVENT_LIMIT_REACHED ->
                    "予定のスキャンが派生イベント上限に達したため、対象期間の一部のみを処理しました。"
                ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE -> "対象期間に写真がありません。"
                ConnectorScanIssueCode.PHOTO_METADATA_LIMIT_REACHED ->
                    "写真のスキャンがメタデータ上限に達したため、対象期間の一部のみを処理しました。"
                ConnectorScanIssueCode.NO_APP_USAGE_IN_RANGE -> "対象期間にアプリ使用履歴がありません。"
                ConnectorScanIssueCode.APP_USAGE_EVENT_HISTORY_LIMITED ->
                    "Androidがアプリ使用イベントを保持する期間は限られるため、古い履歴や期間の境界をまたぐ使用は一部欠ける場合があります。"
                ConnectorScanIssueCode.APP_USAGE_EVENT_LIMIT_REACHED ->
                    "アプリ使用スキャンが一時イベント上限に達したため、不完全な結果は保存しませんでした。"
                ConnectorScanIssueCode.APP_USAGE_DERIVED_OUTPUT_LIMIT_REACHED ->
                    "アプリ使用スキャンが派生セッション上限に達したため、対象期間の一部のみを処理しました。"
                ConnectorScanIssueCode.NO_LAST_KNOWN_LOCATION -> "利用できる最終位置情報がありません。"
                ConnectorScanIssueCode.NOTIFICATION_ALLOWLIST_EMPTY -> "通知の許可リストにアプリを1つ以上追加してください。"
                ConnectorScanIssueCode.NOTIFICATION_HISTORY_UNAVAILABLE -> "許可したアプリの新しい通知のみ到着時にインデックスされます。"
                ConnectorScanIssueCode.NO_LOCAL_DOCUMENTS_SELECTED -> "ローカル文書が選択されていません。"
                ConnectorScanIssueCode.LOCAL_DOCUMENT_PERMISSION_REVOKED -> "選択した文書の読み取り権限が取り消されました。"
                ConnectorScanIssueCode.LOCAL_DOCUMENT_TYPE_UNSUPPORTED -> "この文書形式はサポートされていません。"
                ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED -> "選択した文書を読み取れません。"
                ConnectorScanIssueCode.LOCAL_DOCUMENT_SELECTION_LIMIT_REACHED ->
                    "選択したローカル文書がスキャン上限を超えています。"
                ConnectorScanIssueCode.LOCAL_DOCUMENT_TEXT_LIMIT_REACHED ->
                    "ローカルテキスト文書が一時テキスト上限を超えました。"
                ConnectorScanIssueCode.DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED ->
                    "ローカル文書スキャンが派生ページ上限に達しました。"
                ConnectorScanIssueCode.DOCUMENT_FILE_TOO_LARGE -> "文書がサイズ上限を超えています。"
                ConnectorScanIssueCode.DOCUMENT_SIZE_UNKNOWN -> "文書サイズを安全に確認できません。"
                ConnectorScanIssueCode.DOCUMENT_NOT_SEEKABLE -> "文書が安全なランダムアクセスに対応していません。"
                ConnectorScanIssueCode.PDF_PAGE_LIMIT_EXCEEDED -> "PDFがページ上限を超えています。"
                ConnectorScanIssueCode.PDF_PASSWORD_REQUIRED -> "PDFを開くにはパスワードが必要です。"
                ConnectorScanIssueCode.PDF_MALFORMED -> "PDFが破損しているか、未対応の形式です。"
                ConnectorScanIssueCode.PDF_PAGE_DIMENSIONS_UNSUPPORTED -> "未対応サイズのPDFページがあります。"
                ConnectorScanIssueCode.OCR_MODEL_UNAVAILABLE -> "必要な端末内OCR言語データがインストールされていません。"
                ConnectorScanIssueCode.OCR_PAGE_LIMIT_REACHED -> "PDFが文書ごとのOCRページ上限を超えています。"
                ConnectorScanIssueCode.OCR_TIMED_OUT -> "端末内OCRが時間制限を超えました。"
                ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED -> "非公開の文書処理プロセスが予期せず停止しました。"
                ConnectorScanIssueCode.DOCUMENT_PROCESS_TIMED_OUT -> "文書処理が時間制限を超えました。"
                ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT -> "文書から抽出可能なテキストが見つかりません。"
                ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX -> "文書の一部のみをインデックスしました。"
            }

            GrayinLanguage.ENGLISH -> code.defaultEnglish
        }
    }

    fun ocrLanguageDataTitle(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "로컬 OCR 언어 데이터"
        GrayinLanguage.JAPANESE -> "ローカルOCR言語データ"
        GrayinLanguage.ENGLISH -> "Local OCR language data"
    }

    fun ocrLanguageDataDisclosure(): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "PDF와 페이지 이미지는 기기에서만 처리되며 업로드되지 않습니다. OCR 네트워크는 사용자가 선택한 공개 언어 데이터 다운로드에만 쓰입니다. 호스트는 선택 언어 경로와 IP 등 네트워크 메타데이터를 볼 수 있지만 문서 정보는 받지 않습니다."

        GrayinLanguage.JAPANESE ->
            "PDFとページ画像は端末内だけで処理され、アップロードされません。OCRのネットワーク利用は、選択した公開言語データのダウンロードだけです。ホストには選択言語のパスやIPなどの通信情報が見えますが、文書情報は送信されません。"

        GrayinLanguage.ENGLISH ->
            "PDFs and page images stay on this device and are never uploaded. For OCR, network access is used only to download language data you select. The host can see the selected pack path and network metadata such as IP address, but receives no document data."
    }

    fun ocrLanguagePackSize(size: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "정확한 다운로드 크기: $size"
        GrayinLanguage.JAPANESE -> "正確なダウンロードサイズ: $size"
        GrayinLanguage.ENGLISH -> "Exact download size: $size"
    }

    fun ocrLanguagePackLicense(license: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "라이선스: $license"
        GrayinLanguage.JAPANESE -> "ライセンス: $license"
        GrayinLanguage.ENGLISH -> "License: $license"
    }

    fun ocrLanguagePackCatalogCommit(commit: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "고정 소스: tessdata_fast @ $commit"
        GrayinLanguage.JAPANESE -> "固定ソース: tessdata_fast @ $commit"
        GrayinLanguage.ENGLISH -> "Pinned source: tessdata_fast @ $commit"
    }

    fun ocrLanguagePackRequiresUnmeteredNetwork(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "Wi-Fi 또는 무제한 네트워크와 충분한 저장 공간이 필요합니다."
        GrayinLanguage.JAPANESE -> "Wi-Fiまたは従量制でないネットワークと十分な空き容量が必要です。"
        GrayinLanguage.ENGLISH -> "Requires Wi-Fi or another unmetered network and sufficient storage."
    }

    fun ocrLanguagePackProgress(progressPercent: Int): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "다운로드 진행률: ${progressPercent.coerceIn(0, 100)}%"
        GrayinLanguage.JAPANESE -> "ダウンロード進捗: ${progressPercent.coerceIn(0, 100)}%"
        GrayinLanguage.ENGLISH -> "Download progress: ${progressPercent.coerceIn(0, 100)}%"
    }

    fun ocrLanguagePackDownloadAction(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "OCR 언어 데이터 다운로드"
        GrayinLanguage.JAPANESE -> "OCR言語データをダウンロード"
        GrayinLanguage.ENGLISH -> "Download OCR language data"
    }

    fun ocrLanguagePackCancelAction(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "다운로드 취소"
        GrayinLanguage.JAPANESE -> "ダウンロードをキャンセル"
        GrayinLanguage.ENGLISH -> "Cancel download"
    }

    fun ocrLanguagePackDeleteAction(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "OCR 언어 데이터 삭제"
        GrayinLanguage.JAPANESE -> "OCR言語データを削除"
        GrayinLanguage.ENGLISH -> "Delete OCR language data"
    }

    fun ocrLanguagePackName(pack: OcrLanguagePack): String = when (pack) {
        OcrLanguagePack.ENGLISH -> when (languageCode) {
            GrayinLanguage.KOREAN -> "영어"
            GrayinLanguage.JAPANESE -> "英語"
            GrayinLanguage.ENGLISH -> "English"
        }

        OcrLanguagePack.KOREAN -> when (languageCode) {
            GrayinLanguage.KOREAN -> "한국어"
            GrayinLanguage.JAPANESE -> "韓国語"
            GrayinLanguage.ENGLISH -> "Korean"
        }

        OcrLanguagePack.JAPANESE -> when (languageCode) {
            GrayinLanguage.KOREAN -> "일본어"
            GrayinLanguage.JAPANESE -> "日本語"
            GrayinLanguage.ENGLISH -> "Japanese"
        }
    }

    fun ocrLanguagePackStatus(status: OcrLanguagePackStatus): String = when (languageCode) {
        GrayinLanguage.KOREAN -> when (status) {
            OcrLanguagePackStatus.NOT_INSTALLED -> "상태: 설치되지 않음"
            OcrLanguagePackStatus.QUEUED -> "상태: 다운로드 대기 중"
            OcrLanguagePackStatus.DOWNLOADING -> "상태: 다운로드 중"
            OcrLanguagePackStatus.READY -> "상태: 준비됨"
            OcrLanguagePackStatus.FAILED -> "상태: 실패"
        }

        GrayinLanguage.JAPANESE -> when (status) {
            OcrLanguagePackStatus.NOT_INSTALLED -> "状態: 未インストール"
            OcrLanguagePackStatus.QUEUED -> "状態: ダウンロード待ち"
            OcrLanguagePackStatus.DOWNLOADING -> "状態: ダウンロード中"
            OcrLanguagePackStatus.READY -> "状態: 準備完了"
            OcrLanguagePackStatus.FAILED -> "状態: 失敗"
        }

        GrayinLanguage.ENGLISH -> when (status) {
            OcrLanguagePackStatus.NOT_INSTALLED -> "Status: not installed"
            OcrLanguagePackStatus.QUEUED -> "Status: queued"
            OcrLanguagePackStatus.DOWNLOADING -> "Status: downloading"
            OcrLanguagePackStatus.READY -> "Status: ready"
            OcrLanguagePackStatus.FAILED -> "Status: failed"
        }
    }

    fun ocrLanguagePackFailure(code: OcrLanguagePackFailureCode): String = when (languageCode) {
        GrayinLanguage.KOREAN -> when (code) {
            OcrLanguagePackFailureCode.REDIRECT_REJECTED -> "고정 다운로드 주소가 다른 주소로 이동해 거부했습니다."
            OcrLanguagePackFailureCode.HTTP_REJECTED -> "언어 데이터 호스트가 다운로드를 거부했습니다."
            OcrLanguagePackFailureCode.SERVER_ERROR -> "언어 데이터 호스트를 일시적으로 사용할 수 없습니다."
            OcrLanguagePackFailureCode.CONTENT_TYPE_INVALID -> "언어 데이터 응답 형식이 올바르지 않습니다."
            OcrLanguagePackFailureCode.CONTENT_ENCODING_INVALID -> "언어 데이터 응답 인코딩이 올바르지 않습니다."
            OcrLanguagePackFailureCode.SIZE_MISMATCH -> "언어 데이터 크기가 고정 카탈로그와 다릅니다."
            OcrLanguagePackFailureCode.CHECKSUM_MISMATCH -> "언어 데이터 무결성 검증에 실패했습니다."
            OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE -> "네트워크 또는 로컬 파일 작업에 실패했습니다."
            OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED -> "검증된 언어 데이터를 안전하게 설치하지 못했습니다."
        }

        GrayinLanguage.JAPANESE -> when (code) {
            OcrLanguagePackFailureCode.REDIRECT_REJECTED -> "固定ダウンロード先が転送を返したため拒否しました。"
            OcrLanguagePackFailureCode.HTTP_REJECTED -> "言語データのホストがダウンロードを拒否しました。"
            OcrLanguagePackFailureCode.SERVER_ERROR -> "言語データのホストを一時的に利用できません。"
            OcrLanguagePackFailureCode.CONTENT_TYPE_INVALID -> "言語データの応答形式が正しくありません。"
            OcrLanguagePackFailureCode.CONTENT_ENCODING_INVALID -> "言語データの応答エンコードが正しくありません。"
            OcrLanguagePackFailureCode.SIZE_MISMATCH -> "言語データのサイズが固定カタログと一致しません。"
            OcrLanguagePackFailureCode.CHECKSUM_MISMATCH -> "言語データの整合性検証に失敗しました。"
            OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE -> "ネットワークまたはローカルファイル処理に失敗しました。"
            OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED -> "検証済み言語データを安全にインストールできませんでした。"
        }

        GrayinLanguage.ENGLISH -> when (code) {
            OcrLanguagePackFailureCode.REDIRECT_REJECTED -> "The fixed download address returned a redirect and was rejected."
            OcrLanguagePackFailureCode.HTTP_REJECTED -> "The language-data host rejected the download."
            OcrLanguagePackFailureCode.SERVER_ERROR -> "The language-data host is temporarily unavailable."
            OcrLanguagePackFailureCode.CONTENT_TYPE_INVALID -> "The language-data response type was invalid."
            OcrLanguagePackFailureCode.CONTENT_ENCODING_INVALID -> "The language-data response encoding was invalid."
            OcrLanguagePackFailureCode.SIZE_MISMATCH -> "The language-data size did not match the fixed catalog."
            OcrLanguagePackFailureCode.CHECKSUM_MISMATCH -> "Language-data integrity verification failed."
            OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE -> "A network or local file operation failed."
            OcrLanguagePackFailureCode.ATOMIC_INSTALL_FAILED -> "The verified language data could not be installed safely."
        }
    }

    fun ocrLanguagePackQueued(name: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "$name OCR 언어 데이터 다운로드를 대기열에 추가했습니다. 무제한 네트워크에서 실행됩니다."
        GrayinLanguage.JAPANESE -> "$name OCR言語データのダウンロードを待機列に追加しました。従量制でないネットワークで実行されます。"
        GrayinLanguage.ENGLISH -> "Queued $name OCR language data. It runs on an unmetered network."
    }

    fun ocrLanguagePackCanceled(name: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "$name OCR 언어 데이터 다운로드를 취소했습니다."
        GrayinLanguage.JAPANESE -> "$name OCR言語データのダウンロードをキャンセルしました。"
        GrayinLanguage.ENGLISH -> "Canceled $name OCR language-data download."
    }

    fun ocrLanguagePackDeleted(name: String): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "$name OCR 언어 데이터를 삭제했습니다."
        GrayinLanguage.JAPANESE -> "$name OCR言語データを削除しました。"
        GrayinLanguage.ENGLISH -> "Deleted $name OCR language data."
    }

    fun ocrLanguagePackActionFailed(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "OCR 언어 데이터 작업을 완료하지 못했습니다."
        GrayinLanguage.JAPANESE -> "OCR言語データの操作を完了できませんでした。"
        GrayinLanguage.ENGLISH -> "Could not complete the OCR language-data action."
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
            GrayinLanguage.KOREAN -> "로컬 문서 파생 이벤트 ${count}개를 인덱싱했습니다."
            GrayinLanguage.JAPANESE -> "ローカル文書の派生イベントを${count}件インデックスしました。"
            GrayinLanguage.ENGLISH -> "Indexed $count local document derived event(s)."
        }
    }

    fun deletedLocalFileEvents(count: Int): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "로컬 문서 파생 이벤트 ${count}개를 삭제했습니다."
            GrayinLanguage.JAPANESE -> "ローカル文書の派生イベントを${count}件削除しました。"
            GrayinLanguage.ENGLISH -> "Deleted $count local document derived event(s)."
        }
    }

    fun localDocumentSupportDisclosure(): String = when (languageCode) {
        GrayinLanguage.KOREAN ->
            "지원 형식: Text, Markdown, PDF. 원문은 일시적으로만 읽고 PDF/OCR은 기기 내 별도 전용 프로세스에서 처리합니다."
        GrayinLanguage.JAPANESE ->
            "対応形式: Text、Markdown、PDF。原文は一時的にのみ読み取り、PDF/OCRは端末内の非公開の別プロセスで処理します。"
        GrayinLanguage.ENGLISH ->
            "Supported: Text, Markdown, and PDF. Originals are read transiently; PDF/OCR runs in a private, separate on-device process."
    }

    fun localDocumentRevokeAllAction(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "모든 로컬 문서 권한 해제"
        GrayinLanguage.JAPANESE -> "すべてのローカル文書権限を解除"
        GrayinLanguage.ENGLISH -> "Revoke all local documents"
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

    fun onlineEnrichmentSaved(enabled: Boolean): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> if (enabled) "외부 장소·날씨 enrichment를 켰습니다." else "외부 장소·날씨 enrichment를 껐습니다."
            GrayinLanguage.JAPANESE -> if (enabled) "外部の場所・天気enrichmentをオンにしました。" else "外部の場所・天気enrichmentをオフにしました。"
            GrayinLanguage.ENGLISH -> if (enabled) "Enabled external place and weather enrichment." else "Disabled external place and weather enrichment."
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

    fun decreaseHourDescription(label: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$label 1시간 줄이기"
            GrayinLanguage.JAPANESE -> "${label}を1時間戻す"
            GrayinLanguage.ENGLISH -> "Move $label back by one hour"
        }
    }

    fun increaseHourDescription(label: String): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> "$label 1시간 늘리기"
            GrayinLanguage.JAPANESE -> "${label}を1時間進める"
            GrayinLanguage.ENGLISH -> "Move $label forward by one hour"
        }
    }

    fun indexingStatusRows(
        status: IndexingStatusUiState,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val running = status.runningSourceNames.joinToString().ifBlank { localizedNone() }
        val lastQueueCompletion = status.lastQueueCompletionAt
            ?.let { formatIndexingInstant(it, zoneId) }
            ?: localizedNever()
        val lastAutomaticCheck = status.lastAutomaticCheckedAt
            ?.let { formatIndexingInstant(it, zoneId) }
            ?: localizedNever()
        val lastAutomaticCompletion = status.lastAutomaticCompletedAt
            ?.let { formatIndexingInstant(it, zoneId) }
            ?: localizedNever()
        val outcome = status.lastAutomaticOutcome?.let(::automaticOutcomeLabel) ?: localizedNotRun()
        val reason = status.lastAutomaticSkipReason?.let(::indexingSkipReasonLabel)
            ?: status.lastAutomaticFailureCode?.let(::indexingFailureLabel)
        return when (languageCode) {
            GrayinLanguage.KOREAN -> listOf(
                "대기 중: ${status.queueDepth}개",
                "실행 중 소스: $running",
                "최근 큐 완료: $lastQueueCompletion",
                "최근 자동 활동: $lastAutomaticCheck",
                "최근 자동 활동 종료: $lastAutomaticCompletion",
                "자동 결과: $outcome${reason?.let { " · $it" }.orEmpty()}",
                "최근 자동 파생 이벤트: ${status.lastAutomaticIndexedEventCount}개",
            )

            GrayinLanguage.JAPANESE -> listOf(
                "待機中: ${status.queueDepth}件",
                "実行中のソース: $running",
                "直近のキュー完了: $lastQueueCompletion",
                "直近の自動動作: $lastAutomaticCheck",
                "直近の自動動作終了: $lastAutomaticCompletion",
                "自動処理の結果: $outcome${reason?.let { " · $it" }.orEmpty()}",
                "直近の自動派生イベント: ${status.lastAutomaticIndexedEventCount}件",
            )

            GrayinLanguage.ENGLISH -> listOf(
                "Queued: ${status.queueDepth}",
                "Running sources: $running",
                "Last queue completion: $lastQueueCompletion",
                "Latest automatic activity: $lastAutomaticCheck",
                "Latest automatic activity completion: $lastAutomaticCompletion",
                "Automatic result: $outcome${reason?.let { " · $it" }.orEmpty()}",
                "Last automatic derived events: ${status.lastAutomaticIndexedEventCount}",
            )
        }
    }

    fun indexingLiveStatus(status: IndexingStatusUiState): String {
        val outcome = status.lastAutomaticOutcome?.let(::automaticOutcomeLabel) ?: localizedNotRun()
        val reason = status.lastAutomaticSkipReason?.let(::indexingSkipReasonLabel)
            ?: status.lastAutomaticFailureCode?.let(::indexingFailureLabel)
        val automatic = "$outcome${reason?.let { " · $it" }.orEmpty()}"
        return when (languageCode) {
            GrayinLanguage.KOREAN ->
                "인덱싱: 대기 ${status.queueDepth}개, 실행 ${status.runningSourceNames.size}개. 자동: $automatic"
            GrayinLanguage.JAPANESE ->
                "インデックス: 待機${status.queueDepth}件、実行${status.runningSourceNames.size}件。自動: $automatic"
            GrayinLanguage.ENGLISH ->
                "Indexing: ${status.queueDepth} queued, ${status.runningSourceNames.size} running. Automatic: $automatic"
        }
    }

    fun recentIndexingTaskRow(
        task: RecentIndexingTaskUiState,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val reason = task.skipReason?.let(::indexingSkipReasonLabel)
            ?: task.failureCode?.let(::indexingFailureLabel)
        val completed = task.completedAt?.let { formatIndexingInstant(it, zoneId) }
        return buildString {
            append(task.sourceName)
            append(" · ")
            append(indexingTriggerLabel(task.trigger))
            append(" · ")
            append(indexingStateLabel(task.state))
            if (task.indexedEventCount > 0) {
                append(" · ")
                append(
                    when (languageCode) {
                        GrayinLanguage.KOREAN -> "파생 이벤트 ${task.indexedEventCount}개"
                        GrayinLanguage.JAPANESE -> "派生イベント${task.indexedEventCount}件"
                        GrayinLanguage.ENGLISH -> "${task.indexedEventCount} derived event(s)"
                    },
                )
            }
            reason?.let {
                append(" · ")
                append(it)
            }
            completed?.let {
                append(" · ")
                append(it)
            }
        }
    }

    private fun automaticOutcomeLabel(outcome: AutomaticIndexingOutcome): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (outcome) {
                AutomaticIndexingOutcome.RUNNING -> "실행 중"
                AutomaticIndexingOutcome.COMPLETED -> "완료"
                AutomaticIndexingOutcome.SKIPPED -> "건너뜀"
                AutomaticIndexingOutcome.FAILED -> "실패"
            }

            GrayinLanguage.JAPANESE -> when (outcome) {
                AutomaticIndexingOutcome.RUNNING -> "実行中"
                AutomaticIndexingOutcome.COMPLETED -> "完了"
                AutomaticIndexingOutcome.SKIPPED -> "スキップ"
                AutomaticIndexingOutcome.FAILED -> "失敗"
            }

            GrayinLanguage.ENGLISH -> when (outcome) {
                AutomaticIndexingOutcome.RUNNING -> "Running"
                AutomaticIndexingOutcome.COMPLETED -> "Completed"
                AutomaticIndexingOutcome.SKIPPED -> "Skipped"
                AutomaticIndexingOutcome.FAILED -> "Failed"
            }
        }
    }

    private fun indexingSkipReasonLabel(reason: IndexingSkipReason): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (reason) {
                IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED -> "자동 인덱싱 꺼짐"
                IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED -> "자동 설정 변경됨"
                IndexingSkipReason.WORK_MANAGER_STOPPED -> "시스템이 작업을 중지함"
                IndexingSkipReason.INVALID_LOW_USAGE_WINDOW -> "시간창이 올바르지 않음"
                IndexingSkipReason.OUTSIDE_LOW_USAGE_WINDOW -> "설정 시간창 밖"
                IndexingSkipReason.NOT_CHARGING -> "충전 중이 아님"
                IndexingSkipReason.BATTERY_BELOW_MINIMUM -> "배터리 부족"
                IndexingSkipReason.BATTERY_LEVEL_UNKNOWN -> "배터리 상태 확인 불가"
                IndexingSkipReason.THERMAL_STATE_HOT -> "기기 온도 높음"
                IndexingSkipReason.THERMAL_STATE_CRITICAL -> "기기 온도 위험"
                IndexingSkipReason.SOURCE_DISABLED -> "소스 꺼짐"
                IndexingSkipReason.SOURCE_DATA_DELETED -> "사용자가 소스 데이터 삭제"
                IndexingSkipReason.RECONSENT_REQUIRED -> "소스 재연결 필요"
                IndexingSkipReason.MISSING_PERMISSION -> "권한 없음"
                IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE -> "백그라운드 대상 아님"
                IndexingSkipReason.NO_INDEXABLE_DATA -> "인덱싱할 데이터 없음"
            }

            GrayinLanguage.JAPANESE -> when (reason) {
                IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED -> "自動インデックスがオフ"
                IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED -> "自動設定が変更済み"
                IndexingSkipReason.WORK_MANAGER_STOPPED -> "システムが処理を停止"
                IndexingSkipReason.INVALID_LOW_USAGE_WINDOW -> "時間帯が無効"
                IndexingSkipReason.OUTSIDE_LOW_USAGE_WINDOW -> "設定時間帯の外"
                IndexingSkipReason.NOT_CHARGING -> "充電中ではない"
                IndexingSkipReason.BATTERY_BELOW_MINIMUM -> "バッテリー不足"
                IndexingSkipReason.BATTERY_LEVEL_UNKNOWN -> "バッテリー状態不明"
                IndexingSkipReason.THERMAL_STATE_HOT -> "端末温度が高い"
                IndexingSkipReason.THERMAL_STATE_CRITICAL -> "端末温度が危険"
                IndexingSkipReason.SOURCE_DISABLED -> "ソースがオフ"
                IndexingSkipReason.SOURCE_DATA_DELETED -> "ユーザーがソースデータを削除"
                IndexingSkipReason.RECONSENT_REQUIRED -> "ソースの再接続が必要"
                IndexingSkipReason.MISSING_PERMISSION -> "権限なし"
                IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE -> "バックグラウンド対象外"
                IndexingSkipReason.NO_INDEXABLE_DATA -> "対象データなし"
            }

            GrayinLanguage.ENGLISH -> when (reason) {
                IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED -> "Automatic indexing is off"
                IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED -> "Automatic settings changed"
                IndexingSkipReason.WORK_MANAGER_STOPPED -> "System stopped the work"
                IndexingSkipReason.INVALID_LOW_USAGE_WINDOW -> "Invalid indexing window"
                IndexingSkipReason.OUTSIDE_LOW_USAGE_WINDOW -> "Outside the indexing window"
                IndexingSkipReason.NOT_CHARGING -> "Not charging"
                IndexingSkipReason.BATTERY_BELOW_MINIMUM -> "Battery below minimum"
                IndexingSkipReason.BATTERY_LEVEL_UNKNOWN -> "Battery level unavailable"
                IndexingSkipReason.THERMAL_STATE_HOT -> "Device is hot"
                IndexingSkipReason.THERMAL_STATE_CRITICAL -> "Device temperature is critical"
                IndexingSkipReason.SOURCE_DISABLED -> "Source is off"
                IndexingSkipReason.SOURCE_DATA_DELETED -> "Source data deleted by user"
                IndexingSkipReason.RECONSENT_REQUIRED -> "Source reconnection required"
                IndexingSkipReason.MISSING_PERMISSION -> "Permission missing"
                IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE -> "Not background eligible"
                IndexingSkipReason.NO_INDEXABLE_DATA -> "No indexable data"
            }
        }
    }

    fun indexingSkipReason(reason: IndexingSkipReason): String = indexingSkipReasonLabel(reason)

    private fun indexingFailureLabel(code: IndexingFailureCode): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (code) {
                IndexingFailureCode.CONNECTOR_NOT_FOUND -> "커넥터 없음"
                IndexingFailureCode.CONNECTOR_SCAN_FAILED -> "소스 스캔 실패"
                IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT -> "소스 처리 시간 초과"
                IndexingFailureCode.STORE_WRITE_FAILED -> "암호화 저장 실패"
                IndexingFailureCode.LEASE_EXPIRED -> "작업 lease 만료"
                IndexingFailureCode.ATTEMPT_LIMIT_REACHED -> "재시도 한도 도달"
                IndexingFailureCode.INTERNAL_ERROR -> "내부 오류"
            }

            GrayinLanguage.JAPANESE -> when (code) {
                IndexingFailureCode.CONNECTOR_NOT_FOUND -> "コネクタなし"
                IndexingFailureCode.CONNECTOR_SCAN_FAILED -> "ソースのスキャン失敗"
                IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT -> "ソース処理がタイムアウト"
                IndexingFailureCode.STORE_WRITE_FAILED -> "暗号化保存に失敗"
                IndexingFailureCode.LEASE_EXPIRED -> "処理リース期限切れ"
                IndexingFailureCode.ATTEMPT_LIMIT_REACHED -> "再試行上限"
                IndexingFailureCode.INTERNAL_ERROR -> "内部エラー"
            }

            GrayinLanguage.ENGLISH -> when (code) {
                IndexingFailureCode.CONNECTOR_NOT_FOUND -> "Connector not found"
                IndexingFailureCode.CONNECTOR_SCAN_FAILED -> "Source scan failed"
                IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT -> "Source operation timed out"
                IndexingFailureCode.STORE_WRITE_FAILED -> "Encrypted store write failed"
                IndexingFailureCode.LEASE_EXPIRED -> "Work lease expired"
                IndexingFailureCode.ATTEMPT_LIMIT_REACHED -> "Retry limit reached"
                IndexingFailureCode.INTERNAL_ERROR -> "Internal error"
            }
        }
    }

    fun indexingFailure(code: IndexingFailureCode): String = indexingFailureLabel(code)

    private fun indexingTriggerLabel(trigger: IndexingTrigger): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (trigger) {
                IndexingTrigger.MANUAL -> "수동"
                IndexingTrigger.AUTOMATIC -> "자동"
            }

            GrayinLanguage.JAPANESE -> when (trigger) {
                IndexingTrigger.MANUAL -> "手動"
                IndexingTrigger.AUTOMATIC -> "自動"
            }

            GrayinLanguage.ENGLISH -> when (trigger) {
                IndexingTrigger.MANUAL -> "Manual"
                IndexingTrigger.AUTOMATIC -> "Automatic"
            }
        }
    }

    private fun indexingStateLabel(state: RecentIndexingTaskState): String {
        return when (languageCode) {
            GrayinLanguage.KOREAN -> when (state) {
                RecentIndexingTaskState.PENDING -> "대기 중"
                RecentIndexingTaskState.RUNNING -> "실행 중"
                RecentIndexingTaskState.RECOVERY_PENDING -> "복구 대기"
                RecentIndexingTaskState.COMPLETED -> "완료"
                RecentIndexingTaskState.SKIPPED -> "건너뜀"
                RecentIndexingTaskState.FAILED -> "실패"
            }

            GrayinLanguage.JAPANESE -> when (state) {
                RecentIndexingTaskState.PENDING -> "待機中"
                RecentIndexingTaskState.RUNNING -> "実行中"
                RecentIndexingTaskState.RECOVERY_PENDING -> "復旧待ち"
                RecentIndexingTaskState.COMPLETED -> "完了"
                RecentIndexingTaskState.SKIPPED -> "スキップ"
                RecentIndexingTaskState.FAILED -> "失敗"
            }

            GrayinLanguage.ENGLISH -> when (state) {
                RecentIndexingTaskState.PENDING -> "Pending"
                RecentIndexingTaskState.RUNNING -> "Running"
                RecentIndexingTaskState.RECOVERY_PENDING -> "Recovery pending"
                RecentIndexingTaskState.COMPLETED -> "Completed"
                RecentIndexingTaskState.SKIPPED -> "Skipped"
                RecentIndexingTaskState.FAILED -> "Failed"
            }
        }
    }

    private fun formatIndexingInstant(instant: Instant, zoneId: ZoneId): String {
        return INDEXING_TIME_FORMATTER.withZone(zoneId).format(instant)
    }

    private fun localizedNone(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "없음"
        GrayinLanguage.JAPANESE -> "なし"
        GrayinLanguage.ENGLISH -> "None"
    }

    private fun localizedNever(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "기록 없음"
        GrayinLanguage.JAPANESE -> "記録なし"
        GrayinLanguage.ENGLISH -> "No record"
    }

    private fun localizedNotRun(): String = when (languageCode) {
        GrayinLanguage.KOREAN -> "실행 기록 없음"
        GrayinLanguage.JAPANESE -> "実行履歴なし"
        GrayinLanguage.ENGLISH -> "Not run"
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

private val INDEXING_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
    addLocalFile = "Add Text, Markdown, or PDF",
    revoke = "Revoke",
    delete = "Delete",
    indexNow = "Index now",
    indexAllNow = "Index all now",
    indexing = "Indexing",
    automaticIndexing = "Automatic indexing",
    automaticIndexingSettings = "Automatic indexing settings",
    automaticIndexingOn = "On",
    automaticIndexingOff = "Off",
    indexingStatusTitle = "Indexing status",
    recentIndexingTasks = "Recent indexing activity",
    noRecentIndexingTasks = "No indexing activity recorded.",
    invalidAutomaticIndexingWindow = "Start and end must be different before automatic indexing can be enabled.",
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
    reconnectionRequired = "Reconnection required",
    connectorConnectionUnavailable = "Source connection is unavailable.",
    noDerivedEvents = "No derived memory events indexed.",
    noPlaceClusters = "No place clusters indexed.",
    loadingLocalState = "Loading local state.",
    noAnswerAvailable = "No answer available from indexed evidence.",
    cannotAnswerFromIndexedEvidence = "I cannot answer from indexed, cited evidence.",
    noCitedEvidence = "No cited evidence available.",
    addAndIndexLocalFileFirst = "Add and index a local Text, Markdown, or PDF document first.",
    tryIndexingAgain = "Try indexing local evidence again.",
    noMissingSources = "No missing sources for selected evidence.",
    enterMemoryQuestion = "Enter a memory question.",
    noLocalTextEvidenceIndexed = "No local Text, Markdown, or PDF evidence has been indexed.",
    askFromIndexedEvidence = "Ask from indexed evidence after adding and indexing a local document.",
    localFileSelectionFailed = "Local document selection failed.",
    revokeFailed = "Revoke failed.",
    deleteFailed = "Delete failed.",
    indexingFailed = "Indexing failed.",
    searchFailed = "Search failed.",
    selectedLocalFile = "Connected local document. Run Index now to update evidence.",
    unsupportedFileOrPermissionDenied = "Unsupported document, selection limit reached, or read access was not granted.",
    sourcePermissionDenied = "Source permission was not granted.",
    noLocalFilesIndexed = "No local documents indexed.",
    noSourcesReadyToIndex = "No connected sources are ready to index.",
    revokedLocalFiles = "Revoked all selected local document access and deleted derived local document data.",
    networkPermissionRestricted = "Network: typed enrichment and verified fixed-catalog model or OCR data downloads only",
    onlineEnrichmentTitle = "External place and weather enrichment",
    onlineEnrichmentDisclosure = "Optional. Sends only rounded coordinates and a UTC date to fixed providers. Open-Meteo may retain request URL/IP logs for up to 90 days. No source content or stored memory is sent.",
    onlineEnrichmentProviderCredit = "Weather data provider: Open-Meteo (CC BY 4.0)",
    accountAbsent = "Account: absent",
    cloudSyncAbsent = "Cloud sync: absent",
    telemetryAbsent = "Telemetry: absent",
    crashAnalyticsAbsent = "Crash analytics: absent",
    encryptedStoreSqlCipher = "Encrypted store: SQLCipher",
    localGemmaModelTitle = "Local LiteRT-LM model",
    localGemmaModelReady = "Local model status: compatible LiteRT-LM v1 container found; runtime initialization not yet verified.",
    localGemmaModelLoading = "Local model status: initializing on device",
    localGemmaModelUnavailable = "Local model status: no compatible container; Ask uses template fallback.",
    localGemmaModelApkNotBundled = "Model weights are not bundled in the APK; import or download an approved model at runtime.",
    localGemmaModelDownloadGuide = "Get model from Google AI Edge LiteRT-LM Gemma docs or Hugging Face repo: litert-community/gemma-4-E2B-it-litert-lm.",
    localGemmaModelDownloadUrl = "Download page: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "Expected file name: gemma-4-E2B-it.litertlm.",
    localGemmaModelDevInstallGuide = "Current build install path: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/.",
    openLocalGemmaModelDownloadPage = "Open model download page",
    importLocalGemmaModel = "Import compatible LiteRT-LM model file",
    deleteLocalGemmaModel = "Delete imported model file",
    localGemmaModelImported = "Imported a compatible LiteRT-LM container. Ask will initialize it on device when evidence is available.",
    localGemmaModelDeleted = "Deleted imported model file.",
    localGemmaModelDeleteMissing = "No imported model file found.",
    localGemmaModelImportFailed = "Local model file import failed.",
    localGemmaModelDownloadOpenFailed = "Could not open model download page.",
    localGemmaModelInvalidFile = "Select a compatible LiteRT-LM v1 .litertlm file from 1 MiB through 8 GiB.",
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
    addLocalFile = "Text, Markdown 또는 PDF 추가",
    revoke = "권한 해제",
    delete = "삭제",
    indexNow = "지금 인덱싱",
    indexAllNow = "지금 모두 인덱싱",
    indexing = "인덱싱 중",
    automaticIndexing = "자동 인덱싱",
    automaticIndexingSettings = "자동 인덱싱 세부 설정",
    automaticIndexingOn = "켜짐",
    automaticIndexingOff = "꺼짐",
    indexingStatusTitle = "인덱싱 상태",
    recentIndexingTasks = "최근 인덱싱 활동",
    noRecentIndexingTasks = "기록된 인덱싱 활동이 없습니다.",
    invalidAutomaticIndexingWindow = "자동 인덱싱을 켜려면 시작 시간과 종료 시간이 달라야 합니다.",
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
    reconnectionRequired = "재연결 필요",
    connectorConnectionUnavailable = "소스 연결을 사용할 수 없습니다.",
    noDerivedEvents = "인덱싱된 파생 기억 이벤트가 없습니다.",
    noPlaceClusters = "인덱싱된 장소 클러스터가 없습니다.",
    loadingLocalState = "로컬 상태를 불러오는 중입니다.",
    noAnswerAvailable = "인덱싱된 근거에서 나온 답변이 없습니다.",
    cannotAnswerFromIndexedEvidence = "인덱싱되고 인용된 근거만으로는 답할 수 없습니다.",
    noCitedEvidence = "인용된 근거가 없습니다.",
    addAndIndexLocalFileFirst = "먼저 로컬 Text, Markdown 또는 PDF 문서를 추가하고 인덱싱하세요.",
    tryIndexingAgain = "로컬 근거를 다시 인덱싱해 보세요.",
    noMissingSources = "선택된 근거에 부족한 소스가 없습니다.",
    enterMemoryQuestion = "기억 질문을 입력하세요.",
    noLocalTextEvidenceIndexed = "인덱싱된 로컬 Text, Markdown 또는 PDF 근거가 없습니다.",
    askFromIndexedEvidence = "로컬 문서를 추가하고 인덱싱한 뒤 근거에서 질문하세요.",
    localFileSelectionFailed = "로컬 문서 선택에 실패했습니다.",
    revokeFailed = "권한 해제에 실패했습니다.",
    deleteFailed = "삭제에 실패했습니다.",
    indexingFailed = "인덱싱에 실패했습니다.",
    searchFailed = "검색에 실패했습니다.",
    selectedLocalFile = "로컬 문서를 연결했습니다. 근거를 업데이트하려면 지금 인덱싱을 실행하세요.",
    unsupportedFileOrPermissionDenied = "지원하지 않는 문서이거나 선택 한도에 도달했거나 읽기 권한이 허용되지 않았습니다.",
    sourcePermissionDenied = "소스 권한이 허용되지 않았습니다.",
    noLocalFilesIndexed = "인덱싱된 로컬 문서가 없습니다.",
    noSourcesReadyToIndex = "인덱싱할 수 있는 연결된 소스가 없습니다.",
    revokedLocalFiles = "선택한 모든 로컬 문서 접근 권한을 해제하고 파생 로컬 문서 데이터를 삭제했습니다.",
    networkPermissionRestricted = "네트워크: typed 외부 enrichment와 검증된 고정 catalog 모델·OCR 데이터 다운로드만 허용",
    onlineEnrichmentTitle = "외부 장소·날씨 enrichment",
    onlineEnrichmentDisclosure = "선택 기능입니다. 고정 provider에 반올림 좌표와 UTC 날짜만 보냅니다. Open-Meteo는 요청 URL/IP 로그를 최대 90일 보관할 수 있습니다. 소스 내용이나 저장된 기억은 보내지 않습니다.",
    onlineEnrichmentProviderCredit = "날씨 데이터 제공: Open-Meteo (CC BY 4.0)",
    accountAbsent = "계정: 없음",
    cloudSyncAbsent = "클라우드 동기화: 없음",
    telemetryAbsent = "텔레메트리: 없음",
    crashAnalyticsAbsent = "크래시 분석: 없음",
    encryptedStoreSqlCipher = "암호화 저장소: SQLCipher",
    localGemmaModelTitle = "로컬 LiteRT-LM 모델",
    localGemmaModelReady = "로컬 모델 상태: LiteRT-LM v1 컨테이너 호환성 확인됨. 런타임 초기화는 아직 확인되지 않았습니다.",
    localGemmaModelLoading = "로컬 모델 상태: 기기에서 초기화 중",
    localGemmaModelUnavailable = "로컬 모델 상태: 호환 컨테이너 없음. 질문은 템플릿 fallback을 사용합니다.",
    localGemmaModelApkNotBundled = "모델 weight는 APK에 포함되지 않습니다. 승인된 모델을 실행 시 가져오거나 다운로드하세요.",
    localGemmaModelDownloadGuide = "모델 획득: Google AI Edge LiteRT-LM Gemma 문서 또는 Hugging Face repo litert-community/gemma-4-E2B-it-litert-lm.",
    localGemmaModelDownloadUrl = "다운로드 페이지: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "필요 파일명: gemma-4-E2B-it.litertlm.",
    localGemmaModelDevInstallGuide = "현재 빌드 설치 경로: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/.",
    openLocalGemmaModelDownloadPage = "모델 다운로드 페이지 열기",
    importLocalGemmaModel = "호환 LiteRT-LM 모델 파일 가져오기",
    deleteLocalGemmaModel = "가져온 모델 파일 삭제",
    localGemmaModelImported = "호환 LiteRT-LM 컨테이너를 가져왔습니다. 근거가 있으면 기기에서 초기화합니다.",
    localGemmaModelDeleted = "가져온 모델 파일을 삭제했습니다.",
    localGemmaModelDeleteMissing = "가져온 모델 파일이 없습니다.",
    localGemmaModelImportFailed = "로컬 모델 파일 가져오기에 실패했습니다.",
    localGemmaModelDownloadOpenFailed = "모델 다운로드 페이지를 열 수 없습니다.",
    localGemmaModelInvalidFile = "1MiB 이상 8GiB 이하의 호환 LiteRT-LM v1 .litertlm 파일을 선택하세요.",
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
    addLocalFile = "Text、Markdown、PDFを追加",
    revoke = "許可を解除",
    delete = "削除",
    indexNow = "今すぐインデックス",
    indexAllNow = "今すぐすべてインデックス",
    indexing = "インデックス中",
    automaticIndexing = "自動インデックス",
    automaticIndexingSettings = "自動インデックス詳細設定",
    automaticIndexingOn = "オン",
    automaticIndexingOff = "オフ",
    indexingStatusTitle = "インデックス状態",
    recentIndexingTasks = "最近のインデックス動作",
    noRecentIndexingTasks = "インデックス動作の記録はありません。",
    invalidAutomaticIndexingWindow = "自動インデックスを有効にするには開始時刻と終了時刻を変えてください。",
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
    reconnectionRequired = "再接続が必要",
    connectorConnectionUnavailable = "ソース接続を利用できません。",
    noDerivedEvents = "インデックス済みの派生記憶イベントはありません。",
    noPlaceClusters = "インデックス済みの場所クラスタはありません。",
    loadingLocalState = "ローカル状態を読み込み中です。",
    noAnswerAvailable = "インデックス済みの根拠から得られる回答はありません。",
    cannotAnswerFromIndexedEvidence = "インデックス済みで引用可能な根拠だけでは回答できません。",
    noCitedEvidence = "引用済みの根拠はありません。",
    addAndIndexLocalFileFirst = "まずローカルのText、Markdown、PDF文書を追加してインデックスしてください。",
    tryIndexingAgain = "ローカル根拠をもう一度インデックスしてください。",
    noMissingSources = "選択された根拠に不足しているソースはありません。",
    enterMemoryQuestion = "記憶の質問を入力してください。",
    noLocalTextEvidenceIndexed = "ローカルのText、Markdown、PDF根拠はインデックスされていません。",
    askFromIndexedEvidence = "ローカル文書を追加してインデックスした後、根拠に基づいて質問してください。",
    localFileSelectionFailed = "ローカル文書の選択に失敗しました。",
    revokeFailed = "許可の解除に失敗しました。",
    deleteFailed = "削除に失敗しました。",
    indexingFailed = "インデックスに失敗しました。",
    searchFailed = "検索に失敗しました。",
    selectedLocalFile = "ローカル文書を接続しました。根拠を更新するには今すぐインデックスしてください。",
    unsupportedFileOrPermissionDenied = "未対応の文書、選択上限到達、または読み取り許可がありません。",
    sourcePermissionDenied = "ソース権限が許可されていません。",
    noLocalFilesIndexed = "インデックス済みのローカル文書はありません。",
    noSourcesReadyToIndex = "インデックス可能な接続済みソースがありません。",
    revokedLocalFiles = "選択したすべてのローカル文書へのアクセス許可を解除し、派生ローカル文書データを削除しました。",
    networkPermissionRestricted = "ネットワーク: typed外部enrichmentと検証済み固定catalogのモデル・OCRデータのダウンロードのみ許可",
    onlineEnrichmentTitle = "外部の場所・天気enrichment",
    onlineEnrichmentDisclosure = "任意機能です。固定providerへ丸めた座標とUTC日付だけを送信します。Open-MeteoはリクエストURL/IPログを最大90日保持する場合があります。ソース内容や保存済み記憶は送信しません。",
    onlineEnrichmentProviderCredit = "天気データ提供: Open-Meteo (CC BY 4.0)",
    accountAbsent = "アカウント: なし",
    cloudSyncAbsent = "クラウド同期: なし",
    telemetryAbsent = "テレメトリー: なし",
    crashAnalyticsAbsent = "クラッシュ分析: なし",
    encryptedStoreSqlCipher = "暗号化ストア: SQLCipher",
    localGemmaModelTitle = "ローカルLiteRT-LMモデル",
    localGemmaModelReady = "ローカルモデル状態: LiteRT-LM v1コンテナ互換性を確認済み。ランタイム初期化は未確認です。",
    localGemmaModelLoading = "ローカルモデル状態: 端末上で初期化中",
    localGemmaModelUnavailable = "ローカルモデル状態: 互換コンテナなし。質問はテンプレートfallbackを使います。",
    localGemmaModelApkNotBundled = "モデルweightはAPKに含まれません。承認済みモデルを実行時にインポートまたはダウンロードしてください。",
    localGemmaModelDownloadGuide = "モデル入手先: Google AI Edge LiteRT-LM Gemma docs または Hugging Face repo litert-community/gemma-4-E2B-it-litert-lm。",
    localGemmaModelDownloadUrl = "ダウンロードページ: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    localGemmaModelFileGuide = "必要なファイル名: gemma-4-E2B-it.litertlm。",
    localGemmaModelDevInstallGuide = "現在のビルドの設置先: adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/。",
    openLocalGemmaModelDownloadPage = "モデルダウンロードページを開く",
    importLocalGemmaModel = "互換LiteRT-LMモデルファイルをインポート",
    deleteLocalGemmaModel = "インポート済みモデルファイルを削除",
    localGemmaModelImported = "互換LiteRT-LMコンテナをインポートしました。根拠がある場合、端末上で初期化します。",
    localGemmaModelDeleted = "インポート済みモデルファイルを削除しました。",
    localGemmaModelDeleteMissing = "インポート済みモデルファイルはありません。",
    localGemmaModelImportFailed = "ローカルモデルファイルのインポートに失敗しました。",
    localGemmaModelDownloadOpenFailed = "モデルダウンロードページを開けません。",
    localGemmaModelInvalidFile = "1MiB以上8GiB以下の互換LiteRT-LM v1 .litertlmファイルを選択してください。",
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

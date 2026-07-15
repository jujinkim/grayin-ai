package ai.grayin.app

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.ai.ModelDownloadFailureCode
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingSkipReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.text.NumberFormat

data class TimelineRowPresentation(
    val kind: DerivedMemoryEventKind,
    val occurredAt: Instant,
    val endedAt: Instant?,
    val confidence: ConfidenceLevel,
)

data class PlaceRowPresentation(
    val label: String?,
    val regionLabel: String?,
    val centroidLatitude: Double?,
    val centroidLongitude: Double?,
    val radiusMeters: Double?,
    val firstSeenAt: Instant?,
    val lastSeenAt: Instant?,
    val visitCount: Int,
    val confidence: ConfidenceLevel,
)

data class IndexedDateRangePresentation(
    val from: Instant,
    val untilExclusive: Instant,
)

object DerivedMemoryPresentationMapper {
    fun timeline(events: List<DerivedMemoryEvent>): List<TimelineRowPresentation> {
        return events.sortedByDescending { it.startedAt ?: it.createdAt }
            .map { event ->
                TimelineRowPresentation(
                    kind = event.kind,
                    occurredAt = event.startedAt ?: event.createdAt,
                    endedAt = event.endedAt,
                    confidence = event.confidence,
                )
            }
    }

    fun places(clusters: List<PlaceCluster>): List<PlaceRowPresentation> {
        return clusters.sortedByDescending { it.lastSeenAt ?: it.firstSeenAt ?: Instant.EPOCH }
            .map { cluster ->
                PlaceRowPresentation(
                    label = cluster.label,
                    regionLabel = cluster.regionLabel,
                    centroidLatitude = cluster.centroidLatitude,
                    centroidLongitude = cluster.centroidLongitude,
                    radiusMeters = cluster.radiusMeters,
                    firstSeenAt = cluster.firstSeenAt,
                    lastSeenAt = cluster.lastSeenAt,
                    visitCount = cluster.visitCount,
                    confidence = cluster.confidence,
                )
            }
    }
}

fun GrayinStrings.sensitivityLabel(level: SensitivityLevel): String = when (languageCode) {
    GrayinLanguage.KOREAN -> when (level) {
        SensitivityLevel.LOW -> "낮은 민감도"
        SensitivityLevel.MEDIUM -> "중간 민감도"
        SensitivityLevel.HIGH -> "높은 민감도"
        SensitivityLevel.VERY_HIGH -> "매우 높은 민감도"
    }

    GrayinLanguage.JAPANESE -> when (level) {
        SensitivityLevel.LOW -> "低い機密性"
        SensitivityLevel.MEDIUM -> "中程度の機密性"
        SensitivityLevel.HIGH -> "高い機密性"
        SensitivityLevel.VERY_HIGH -> "非常に高い機密性"
    }

    GrayinLanguage.ENGLISH -> when (level) {
        SensitivityLevel.LOW -> "Low sensitivity"
        SensitivityLevel.MEDIUM -> "Medium sensitivity"
        SensitivityLevel.HIGH -> "High sensitivity"
        SensitivityLevel.VERY_HIGH -> "Very high sensitivity"
    }
}

fun GrayinStrings.processingStateLabel(state: ProcessingState): String = when (languageCode) {
    GrayinLanguage.KOREAN -> when (state) {
        ProcessingState.PENDING -> "대기 중"
        ProcessingState.RUNNING -> "실행 중"
        ProcessingState.COMPLETED -> "완료"
        ProcessingState.FAILED -> "실패"
        ProcessingState.SKIPPED -> "건너뜀"
        ProcessingState.STALE -> "오래됨"
    }

    GrayinLanguage.JAPANESE -> when (state) {
        ProcessingState.PENDING -> "待機中"
        ProcessingState.RUNNING -> "実行中"
        ProcessingState.COMPLETED -> "完了"
        ProcessingState.FAILED -> "失敗"
        ProcessingState.SKIPPED -> "スキップ"
        ProcessingState.STALE -> "古い状態"
    }

    GrayinLanguage.ENGLISH -> when (state) {
        ProcessingState.PENDING -> "Pending"
        ProcessingState.RUNNING -> "Running"
        ProcessingState.COMPLETED -> "Completed"
        ProcessingState.FAILED -> "Failed"
        ProcessingState.SKIPPED -> "Skipped"
        ProcessingState.STALE -> "Stale"
    }
}

fun GrayinStrings.confidenceLabel(confidence: ConfidenceLevel): String = when (languageCode) {
    GrayinLanguage.KOREAN -> when (confidence) {
        ConfidenceLevel.UNKNOWN -> "알 수 없음"
        ConfidenceLevel.LOW -> "낮음"
        ConfidenceLevel.MEDIUM -> "중간"
        ConfidenceLevel.HIGH -> "높음"
    }

    GrayinLanguage.JAPANESE -> when (confidence) {
        ConfidenceLevel.UNKNOWN -> "不明"
        ConfidenceLevel.LOW -> "低"
        ConfidenceLevel.MEDIUM -> "中"
        ConfidenceLevel.HIGH -> "高"
    }

    GrayinLanguage.ENGLISH -> when (confidence) {
        ConfidenceLevel.UNKNOWN -> "Unknown"
        ConfidenceLevel.LOW -> "Low"
        ConfidenceLevel.MEDIUM -> "Medium"
        ConfidenceLevel.HIGH -> "High"
    }
}

fun GrayinStrings.timelineRow(
    row: TimelineRowPresentation,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val occurred = formatDateTime(row.occurredAt, zoneId)
    val interval = row.endedAt
        ?.takeIf { it.isAfter(row.occurredAt) }
        ?.let { ended -> "$occurred – ${formatDateTime(ended, zoneId)}" }
        ?: occurred
    val kind = eventKindLabel(row.kind)
    val confidence = confidenceLabel(row.confidence)
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "$interval · $kind · 신뢰도 $confidence"
        GrayinLanguage.JAPANESE -> "$interval · $kind · 信頼度 $confidence"
        GrayinLanguage.ENGLISH -> "$interval · $kind · Confidence $confidence"
    }
}

fun GrayinStrings.placeRow(
    row: PlaceRowPresentation,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val title = row.label?.takeIf { it.isNotBlank() } ?: unnamedPlaceLabel()
    val heading = listOfNotNull(
        title,
        row.regionLabel?.takeIf { it.isNotBlank() },
        coordinateLabel(row.centroidLatitude, row.centroidLongitude),
    ).distinct().joinToString(" · ")
    val details = buildList {
        add(visitCountLabel(row.visitCount))
        if (row.firstSeenAt != null || row.lastSeenAt != null) {
            add(placePeriodLabel(row.firstSeenAt, row.lastSeenAt, zoneId))
        }
        row.radiusMeters?.takeIf { it.isFinite() && it >= 0.0 }?.let { radius ->
            add(radiusLabel(radius))
        }
        add(placeConfidenceLabel(row.confidence))
    }
    return "$heading\n${details.joinToString(" · ")}"
}

fun GrayinStrings.connectorScanDetailRows(
    status: ConnectorScanStatus?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String> {
    if (status == null) return emptyList()
    return buildList {
        add(latestScanLabel(status.processingState, status.scannedAt, zoneId))
        status.missingSources.mapNotNull { it.issueCode }
            .distinct()
            .forEach { issueCode -> add(connectorScanIssue(issueCode)) }
    }
}

fun GrayinStrings.requestedScanDateRangeLabel(
    range: IndexedDateRangePresentation,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val start = range.from.atZone(zoneId).toLocalDate()
    val inclusiveEnd = range.untilExclusive.minusNanos(1).atZone(zoneId).toLocalDate()
    val formattedStart = formatDate(start)
    val formattedEnd = formatDate(inclusiveEnd)
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "최근 요청한 스캔 범위: $formattedStart ~ $formattedEnd"
        GrayinLanguage.JAPANESE -> "最新のリクエスト範囲: $formattedStart ～ $formattedEnd"
        GrayinLanguage.ENGLISH -> "Latest requested scan range: $formattedStart – $formattedEnd"
    }
}

fun GrayinStrings.dateRangeIndexingTitle(): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "기간 인덱싱"
    GrayinLanguage.JAPANESE -> "期間を指定してインデックス"
    GrayinLanguage.ENGLISH -> "Index a date range"
}

fun GrayinStrings.lastDaysLabel(days: Int): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "최근 ${days}일"
    GrayinLanguage.JAPANESE -> "過去${days}日"
    GrayinLanguage.ENGLISH -> "Last $days days"
}

fun GrayinStrings.settingsAccessOpenFailed(): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "Android 권한 설정을 열 수 없습니다."
    GrayinLanguage.JAPANESE -> "Androidの権限設定を開けません。"
    GrayinLanguage.ENGLISH -> "Could not open Android permission settings."
}

fun GrayinStrings.settingsAccessAlreadyPending(): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "다른 Android 권한 설정 결과를 기다리고 있습니다."
    GrayinLanguage.JAPANESE -> "別のAndroid権限設定の結果を待っています。"
    GrayinLanguage.ENGLISH -> "Waiting for another Android permission settings result."
}

fun GrayinStrings.sourceConnectionFailed(): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "소스 연결 상태를 저장하지 못했습니다."
    GrayinLanguage.JAPANESE -> "ソースの接続状態を保存できませんでした。"
    GrayinLanguage.ENGLISH -> "Could not save the source connection state."
}

fun GrayinStrings.connectorConnectionResult(result: ConnectorConnectionResult): String = when (result) {
    is ConnectorConnectionResult.Connected -> connectedConnector(
        connectorName(result.connectorId, result.connectorName),
    )

    is ConnectorConnectionResult.PermissionRequired -> sourcePermissionDenied
    is ConnectorConnectionResult.Unavailable -> connectorConnectionUnavailable
}

fun GrayinStrings.memoryCapabilityLabel(capability: MemoryCapability): String = when (languageCode) {
    GrayinLanguage.KOREAN -> when (capability) {
        MemoryCapability.HAS_TIME -> "시간"
        MemoryCapability.HAS_LOCATION -> "위치"
        MemoryCapability.HAS_MEDIA -> "미디어"
        MemoryCapability.HAS_CALENDAR -> "일정"
        MemoryCapability.HAS_PAYMENT -> "결제"
        MemoryCapability.HAS_DELIVERY -> "배송"
        MemoryCapability.HAS_RESERVATION -> "예약"
        MemoryCapability.HAS_TRANSPORT -> "교통"
        MemoryCapability.HAS_APP_USAGE -> "앱 사용"
        MemoryCapability.HAS_TEXT -> "텍스트"
        MemoryCapability.HAS_PERSON -> "사람"
        MemoryCapability.HAS_VISUAL_LABEL -> "시각 라벨"
    }

    GrayinLanguage.JAPANESE -> when (capability) {
        MemoryCapability.HAS_TIME -> "時刻"
        MemoryCapability.HAS_LOCATION -> "場所"
        MemoryCapability.HAS_MEDIA -> "メディア"
        MemoryCapability.HAS_CALENDAR -> "予定"
        MemoryCapability.HAS_PAYMENT -> "支払い"
        MemoryCapability.HAS_DELIVERY -> "配送"
        MemoryCapability.HAS_RESERVATION -> "予約"
        MemoryCapability.HAS_TRANSPORT -> "交通"
        MemoryCapability.HAS_APP_USAGE -> "アプリ使用"
        MemoryCapability.HAS_TEXT -> "テキスト"
        MemoryCapability.HAS_PERSON -> "人物"
        MemoryCapability.HAS_VISUAL_LABEL -> "視覚ラベル"
    }

    GrayinLanguage.ENGLISH -> when (capability) {
        MemoryCapability.HAS_TIME -> "Time"
        MemoryCapability.HAS_LOCATION -> "Location"
        MemoryCapability.HAS_MEDIA -> "Media"
        MemoryCapability.HAS_CALENDAR -> "Calendar"
        MemoryCapability.HAS_PAYMENT -> "Payment"
        MemoryCapability.HAS_DELIVERY -> "Delivery"
        MemoryCapability.HAS_RESERVATION -> "Reservation"
        MemoryCapability.HAS_TRANSPORT -> "Transport"
        MemoryCapability.HAS_APP_USAGE -> "App usage"
        MemoryCapability.HAS_TEXT -> "Text"
        MemoryCapability.HAS_PERSON -> "Person"
        MemoryCapability.HAS_VISUAL_LABEL -> "Visual label"
    }
}

fun GrayinStrings.localModelFailure(code: ModelDownloadFailureCode): String {
    val reason = when (languageCode) {
        GrayinLanguage.KOREAN -> when (code) {
            ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED -> "다운로드가 구성되지 않음"
            ModelDownloadFailureCode.DOWNLOAD_FAILED -> "다운로드 실패"
            ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED -> "안전한 설치 실패"
            ModelDownloadFailureCode.REDIRECT_REJECTED -> "리디렉션 거부"
            ModelDownloadFailureCode.HTTP_REJECTED -> "HTTP 응답 거부"
            ModelDownloadFailureCode.SERVER_ERROR -> "서버 오류"
            ModelDownloadFailureCode.CONTENT_TYPE_INVALID -> "콘텐츠 형식 불일치"
            ModelDownloadFailureCode.CONTENT_ENCODING_INVALID -> "콘텐츠 인코딩 불일치"
            ModelDownloadFailureCode.SIZE_MISMATCH -> "파일 크기 불일치"
            ModelDownloadFailureCode.CHECKSUM_MISMATCH -> "체크섬 불일치"
            ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE -> "네트워크 또는 파일 오류"
        }

        GrayinLanguage.JAPANESE -> when (code) {
            ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED -> "ダウンロード未設定"
            ModelDownloadFailureCode.DOWNLOAD_FAILED -> "ダウンロード失敗"
            ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED -> "安全なインストールに失敗"
            ModelDownloadFailureCode.REDIRECT_REJECTED -> "リダイレクトを拒否"
            ModelDownloadFailureCode.HTTP_REJECTED -> "HTTP応答を拒否"
            ModelDownloadFailureCode.SERVER_ERROR -> "サーバーエラー"
            ModelDownloadFailureCode.CONTENT_TYPE_INVALID -> "コンテンツ形式が不一致"
            ModelDownloadFailureCode.CONTENT_ENCODING_INVALID -> "コンテンツエンコーディングが不一致"
            ModelDownloadFailureCode.SIZE_MISMATCH -> "ファイルサイズが不一致"
            ModelDownloadFailureCode.CHECKSUM_MISMATCH -> "チェックサムが不一致"
            ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE -> "ネットワークまたはファイルエラー"
        }

        GrayinLanguage.ENGLISH -> when (code) {
            ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED -> "Download is not configured"
            ModelDownloadFailureCode.DOWNLOAD_FAILED -> "Download failed"
            ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED -> "Safe install failed"
            ModelDownloadFailureCode.REDIRECT_REJECTED -> "Redirect rejected"
            ModelDownloadFailureCode.HTTP_REJECTED -> "HTTP response rejected"
            ModelDownloadFailureCode.SERVER_ERROR -> "Server error"
            ModelDownloadFailureCode.CONTENT_TYPE_INVALID -> "Content type mismatch"
            ModelDownloadFailureCode.CONTENT_ENCODING_INVALID -> "Content encoding mismatch"
            ModelDownloadFailureCode.SIZE_MISMATCH -> "File size mismatch"
            ModelDownloadFailureCode.CHECKSUM_MISMATCH -> "Checksum mismatch"
            ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE -> "Network or file error"
        }
    }
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "실패 사유: $reason"
        GrayinLanguage.JAPANESE -> "失敗理由: $reason"
        GrayinLanguage.ENGLISH -> "Failure: $reason"
    }
}

fun GrayinStrings.formatGigabytes(bytes: Long): String {
    return "${formatDecimal(bytes / 1_000_000_000.0)} GB"
}

fun GrayinStrings.formatExactDownloadSize(bytes: Long): String {
    val byteUnit = when (languageCode) {
        GrayinLanguage.KOREAN -> "바이트"
        GrayinLanguage.JAPANESE -> "バイト"
        GrayinLanguage.ENGLISH -> "bytes"
    }
    return "${formatInteger(bytes)} $byteUnit (${formatDecimal(bytes / 1_000_000.0)} MB)"
}

fun GrayinStrings.manualIndexingSkipped(reason: IndexingSkipReason?): String {
    if (reason == null) return indexingFailed
    val detail = indexingSkipReason(reason)
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "인덱싱을 건너뛰었습니다: $detail"
        GrayinLanguage.JAPANESE -> "インデックスをスキップしました: $detail"
        GrayinLanguage.ENGLISH -> "Indexing skipped: $detail"
    }
}

fun GrayinStrings.manualIndexingFailed(code: IndexingFailureCode?): String {
    if (code == null) return indexingFailed
    val detail = indexingFailure(code)
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "인덱싱 실패: $detail"
        GrayinLanguage.JAPANESE -> "インデックス失敗: $detail"
        GrayinLanguage.ENGLISH -> "Indexing failed: $detail"
    }
}

private fun GrayinStrings.eventKindLabel(kind: DerivedMemoryEventKind): String = when (languageCode) {
    GrayinLanguage.KOREAN -> when (kind) {
        DerivedMemoryEventKind.PLACE_VISIT -> "장소 방문"
        DerivedMemoryEventKind.PLACE_CLUSTER -> "장소 기록"
        DerivedMemoryEventKind.CALENDAR_EVENT -> "일정"
        DerivedMemoryEventKind.PHOTO_INDEX -> "사진 메타데이터"
        DerivedMemoryEventKind.PHOTO_CLUSTER -> "사진 묶음"
        DerivedMemoryEventKind.PAYMENT -> "결제 알림 신호"
        DerivedMemoryEventKind.DELIVERY -> "배송 알림 신호"
        DerivedMemoryEventKind.RESERVATION -> "예약 알림 신호"
        DerivedMemoryEventKind.TRANSPORT -> "교통 알림 신호"
        DerivedMemoryEventKind.APP_USAGE -> "앱 사용"
        DerivedMemoryEventKind.LOCAL_FILE_INDEX -> "로컬 문서"
        DerivedMemoryEventKind.DAILY_SUMMARY -> "일일 요약"
        DerivedMemoryEventKind.INFERRED_CONTEXT -> "파생 맥락"
    }

    GrayinLanguage.JAPANESE -> when (kind) {
        DerivedMemoryEventKind.PLACE_VISIT -> "場所への訪問"
        DerivedMemoryEventKind.PLACE_CLUSTER -> "場所の履歴"
        DerivedMemoryEventKind.CALENDAR_EVENT -> "予定"
        DerivedMemoryEventKind.PHOTO_INDEX -> "写真メタデータ"
        DerivedMemoryEventKind.PHOTO_CLUSTER -> "写真グループ"
        DerivedMemoryEventKind.PAYMENT -> "支払い通知シグナル"
        DerivedMemoryEventKind.DELIVERY -> "配送通知シグナル"
        DerivedMemoryEventKind.RESERVATION -> "予約通知シグナル"
        DerivedMemoryEventKind.TRANSPORT -> "交通通知シグナル"
        DerivedMemoryEventKind.APP_USAGE -> "アプリ使用"
        DerivedMemoryEventKind.LOCAL_FILE_INDEX -> "ローカル文書"
        DerivedMemoryEventKind.DAILY_SUMMARY -> "日次サマリー"
        DerivedMemoryEventKind.INFERRED_CONTEXT -> "派生コンテキスト"
    }

    GrayinLanguage.ENGLISH -> when (kind) {
        DerivedMemoryEventKind.PLACE_VISIT -> "Place visit"
        DerivedMemoryEventKind.PLACE_CLUSTER -> "Place history"
        DerivedMemoryEventKind.CALENDAR_EVENT -> "Calendar event"
        DerivedMemoryEventKind.PHOTO_INDEX -> "Photo metadata"
        DerivedMemoryEventKind.PHOTO_CLUSTER -> "Photo cluster"
        DerivedMemoryEventKind.PAYMENT -> "Payment notification signal"
        DerivedMemoryEventKind.DELIVERY -> "Delivery notification signal"
        DerivedMemoryEventKind.RESERVATION -> "Reservation notification signal"
        DerivedMemoryEventKind.TRANSPORT -> "Transport notification signal"
        DerivedMemoryEventKind.APP_USAGE -> "App usage"
        DerivedMemoryEventKind.LOCAL_FILE_INDEX -> "Local document"
        DerivedMemoryEventKind.DAILY_SUMMARY -> "Daily summary"
        DerivedMemoryEventKind.INFERRED_CONTEXT -> "Derived context"
    }
}

private fun GrayinStrings.latestScanLabel(
    state: ProcessingState,
    scannedAt: Instant,
    zoneId: ZoneId,
): String {
    val time = formatDateTime(scannedAt, zoneId)
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "최근 스캔: ${processingStateLabel(state)} · $time"
        GrayinLanguage.JAPANESE -> "最新スキャン: ${processingStateLabel(state)} · $time"
        GrayinLanguage.ENGLISH -> "Latest scan: ${processingStateLabel(state)} · $time"
    }
}

private fun GrayinStrings.unnamedPlaceLabel(): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "이름 없는 장소"
    GrayinLanguage.JAPANESE -> "名称未設定の場所"
    GrayinLanguage.ENGLISH -> "Unnamed place"
}

private fun GrayinStrings.visitCountLabel(count: Int): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "방문 ${count}회"
    GrayinLanguage.JAPANESE -> "訪問 ${count}回"
    GrayinLanguage.ENGLISH -> if (count == 1) "1 visit" else "$count visits"
}

private fun GrayinStrings.placePeriodLabel(
    firstSeenAt: Instant?,
    lastSeenAt: Instant?,
    zoneId: ZoneId,
): String {
    val first = firstSeenAt?.let { formatDateTime(it, zoneId) }
    val last = lastSeenAt?.let { formatDateTime(it, zoneId) }
    val value = when {
        first != null && last != null && first != last -> "$first – $last"
        last != null -> last
        else -> first.orEmpty()
    }
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "기록 기간 $value"
        GrayinLanguage.JAPANESE -> "記録期間 $value"
        GrayinLanguage.ENGLISH -> "Recorded $value"
    }
}

private fun GrayinStrings.radiusLabel(radiusMeters: Double): String {
    val rounded = radiusMeters.coerceAtMost(100_000.0).toInt()
    return when (languageCode) {
        GrayinLanguage.KOREAN -> "반경 약 ${formatInteger(rounded)}m"
        GrayinLanguage.JAPANESE -> "半径約${formatInteger(rounded)}m"
        GrayinLanguage.ENGLISH -> "About ${formatInteger(rounded)} m radius"
    }
}

private fun GrayinStrings.placeConfidenceLabel(confidence: ConfidenceLevel): String = when (languageCode) {
    GrayinLanguage.KOREAN -> "신뢰도 ${confidenceLabel(confidence)}"
    GrayinLanguage.JAPANESE -> "信頼度 ${confidenceLabel(confidence)}"
    GrayinLanguage.ENGLISH -> "Confidence ${confidenceLabel(confidence)}"
}

private fun GrayinStrings.coordinateLabel(latitude: Double?, longitude: Double?): String? {
    if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) return null
    return String.format(locale(), "%.3f, %.3f", latitude, longitude)
}

private fun GrayinStrings.formatDateTime(instant: Instant, zoneId: ZoneId): String {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale())
        .withZone(zoneId)
        .format(instant)
}

private fun GrayinStrings.formatDate(date: java.time.LocalDate): String {
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale())
        .format(date)
}

private fun GrayinStrings.formatInteger(value: Int): String = String.format(locale(), "%,d", value)

private fun GrayinStrings.formatInteger(value: Long): String = NumberFormat.getIntegerInstance(locale()).format(value)

private fun GrayinStrings.formatDecimal(value: Double): String {
    return NumberFormat.getNumberInstance(locale()).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
        isGroupingUsed = true
    }.format(value)
}

private fun GrayinStrings.locale(): Locale = when (languageCode) {
    GrayinLanguage.KOREAN -> Locale.KOREAN
    GrayinLanguage.JAPANESE -> Locale.JAPANESE
    GrayinLanguage.ENGLISH -> Locale.ENGLISH
}

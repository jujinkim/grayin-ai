package ai.grayin.app

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.ai.ModelDownloadFailureCode
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedMemoryPresentationTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `timeline presentation never exposes persisted connector prose`() {
        val event = DerivedMemoryEvent(
            id = "event:calendar:1",
            kind = DerivedMemoryEventKind.CALENDAR_EVENT,
            sourceReferenceIds = listOf("source:calendar:1"),
            summary = "DO NOT DISPLAY CONNECTOR PROSE",
            startedAt = Instant.parse("2026-07-15T01:00:00Z"),
            confidence = ConfidenceLevel.HIGH,
            createdAt = Instant.parse("2026-07-15T02:00:00Z"),
        )

        GrayinLanguageOption.entries.forEach { option ->
            val strings = GrayinText.forOption(option)
            val row = strings.timelineRow(
                DerivedMemoryPresentationMapper.timeline(listOf(event)).single(),
                utc,
            )

            assertFalse(row.contains(event.summary))
            assertTrue(row.contains("2026"))
        }
    }

    @Test
    fun `all typed presentation enums have localized output`() {
        listOf(
            GrayinLanguageOption.ENGLISH,
            GrayinLanguageOption.KOREAN,
            GrayinLanguageOption.JAPANESE,
        ).forEach { option ->
            val strings = GrayinText.forOption(option)
            SensitivityLevel.entries.forEach { level ->
                assertTrue(strings.sensitivityLabel(level).isNotBlank())
            }
            ProcessingState.entries.forEach { state ->
                assertTrue(strings.processingStateLabel(state).isNotBlank())
            }
            ConfidenceLevel.entries.forEach { confidence ->
                assertTrue(strings.confidenceLabel(confidence).isNotBlank())
            }
            MemoryCapability.entries.forEach { capability ->
                val label = strings.memoryCapabilityLabel(capability)
                assertTrue(label.isNotBlank())
                assertFalse(label.contains(capability.name))
            }
            ModelDownloadFailureCode.entries.forEach { failure ->
                val label = strings.localModelFailure(failure)
                assertTrue(label.isNotBlank())
                assertFalse(label.contains(failure.storageKey))
            }
            DerivedMemoryEventKind.entries.forEach { kind ->
                val row = strings.timelineRow(
                    TimelineRowPresentation(
                        kind = kind,
                        occurredAt = Instant.parse("2026-07-15T01:00:00Z"),
                        endedAt = null,
                        confidence = ConfidenceLevel.MEDIUM,
                    ),
                    utc,
                )
                assertTrue(row.isNotBlank())
                assertFalse(row.contains(kind.name))
            }
        }
    }

    @Test
    fun `place presentation uses typed cluster fields and localized fallback`() {
        val cluster = PlaceCluster(
            id = "place-cluster:location:abc",
            centroidLatitude = 37.566,
            centroidLongitude = 126.978,
            radiusMeters = 40.0,
            firstSeenAt = Instant.parse("2026-07-14T02:00:00Z"),
            lastSeenAt = Instant.parse("2026-07-15T02:00:00Z"),
            visitCount = 2,
            confidence = ConfidenceLevel.MEDIUM,
        )
        val presentation = DerivedMemoryPresentationMapper.places(listOf(cluster)).single()

        val korean = GrayinText.forOption(GrayinLanguageOption.KOREAN).placeRow(presentation, utc)
        val japanese = GrayinText.forOption(GrayinLanguageOption.JAPANESE).placeRow(presentation, utc)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH).placeRow(presentation, utc)

        assertTrue(korean.contains("이름 없는 장소"))
        assertTrue(korean.contains("방문 2회"))
        assertTrue(japanese.contains("訪問 2回"))
        assertTrue(english.contains("2 visits"))
        assertTrue(english.contains("37.566"))
    }

    @Test
    fun `scan issue and half-open range are rendered from typed values`() {
        val missing = MissingSource(
            capability = MemoryCapability.HAS_CALENDAR,
            availability = SourceAvailability.NOT_INDEXED,
            explanation = ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE.defaultEnglish,
            connectorId = "calendar",
            issueCode = ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE,
        )
        val status = ConnectorScanStatus(
            connectorId = "calendar",
            processingState = ProcessingState.SKIPPED,
            missingSources = listOf(missing),
            scopeFrom = Instant.parse("2026-07-01T00:00:00Z"),
            scopeUntil = Instant.parse("2026-07-16T00:00:00Z"),
            scannedAt = Instant.parse("2026-07-15T02:00:00Z"),
        )
        val strings = GrayinText.forOption(GrayinLanguageOption.KOREAN)

        val details = strings.connectorScanDetailRows(status, utc)
        val range = strings.requestedScanDateRangeLabel(
            IndexedDateRangePresentation(status.scopeFrom!!, status.scopeUntil!!),
            utc,
        )

        assertTrue(details.any { it.contains("건너뜀") })
        assertTrue(details.any { it.contains("일정이 없습니다") })
        assertTrue(range.contains("최근 요청한 스캔 범위"))
        assertTrue(range.contains("2026. 7. 1"))
        assertTrue(range.contains("2026. 7. 15"))
    }

    @Test
    fun `file sizes use selected language number and unit presentation`() {
        val korean = GrayinText.forOption(GrayinLanguageOption.KOREAN)
            .formatExactDownloadSize(12_345_678L)
        val japanese = GrayinText.forOption(GrayinLanguageOption.JAPANESE)
            .formatExactDownloadSize(12_345_678L)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)
            .formatExactDownloadSize(12_345_678L)

        assertTrue(korean.contains("바이트"))
        assertFalse(korean.contains("bytes"))
        assertTrue(japanese.contains("バイト"))
        assertFalse(japanese.contains("bytes"))
        assertTrue(english.contains("bytes"))
    }
}

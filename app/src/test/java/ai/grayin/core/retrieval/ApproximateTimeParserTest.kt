package ai.grayin.core.retrieval

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproximateTimeParserTest {
    private val parser = ApproximateTimeParser(ZoneId.of("Asia/Seoul"))
    private val now = Instant.parse("2026-07-15T03:00:00Z")

    @Test
    fun parsesKoreanRelativeDay() {
        val range = requireNotNull(parser.parse("어제 어디에 갔어?", now))

        assertEquals("어제", range.label)
        assertEquals(Instant.parse("2026-07-13T15:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-07-14T15:00:00Z"), range.endExclusive)
        assertFalse(range.isFuture)
    }

    @Test
    fun parsesJapaneseFutureWeek() {
        val range = requireNotNull(parser.parse("来週は忙しい？", now))

        assertEquals("来週", range.label)
        assertTrue(range.isFuture)
    }
}

package ai.grayin.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorScanIssueCodeTest {
    @Test
    fun storageKeysAreExplicitUniqueAndStableFormat() {
        val keys = ConnectorScanIssueCode.entries.map { it.storageKey }

        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { key -> key.matches(Regex("[a-z0-9_]+")) })
        ConnectorScanIssueCode.entries.forEach { code ->
            assertEquals(code, ConnectorScanIssueCode.fromStorageKey(code.storageKey))
        }
        assertNull(ConnectorScanIssueCode.fromStorageKey("unknown"))
    }

    @Test
    fun legacyConnectorExplanationsMigrateToSpecificIssueCodes() {
        assertEquals(
            ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED,
            ConnectorScanIssueCode.fromLegacyExplanation("Calendar permission was not granted."),
        )
        assertEquals(
            ConnectorScanIssueCode.NO_PHOTOS_IN_RANGE,
            ConnectorScanIssueCode.fromLegacyExplanation("No photos found in the indexed time window."),
        )
        assertEquals(
            ConnectorScanIssueCode.NOTIFICATION_HISTORY_UNAVAILABLE,
            ConnectorScanIssueCode.fromLegacyExplanation(
                "Notification source indexes allowlisted new notifications as they arrive; " +
                    "there is no historical notification scan.",
            ),
        )
        assertEquals(
            ConnectorScanIssueCode.LOCAL_DOCUMENT_TYPE_UNSUPPORTED,
            ConnectorScanIssueCode.fromLegacyExplanation(
                "Only .txt and .md files are supported in this milestone.",
            ),
        )
        assertNull(ConnectorScanIssueCode.fromLegacyExplanation("RAW_SENTINEL"))
    }
}

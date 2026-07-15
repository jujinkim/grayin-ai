package ai.grayin.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportImportPolicyTest {
    @Test
    fun allowedSectionsAreExactlyTheSevenDerivedSnapshotSections() {
        assertEquals(
            setOf(
                ExportDataSection.SOURCE_REFERENCES,
                ExportDataSection.DERIVED_MEMORY_EVENTS,
                ExportDataSection.CITATIONS,
                ExportDataSection.DAILY_SUMMARIES,
                ExportDataSection.PLACE_CLUSTERS,
                ExportDataSection.APP_USAGE_SUMMARIES,
                ExportDataSection.CONNECTOR_SCAN_STATUSES,
            ),
            ExportImportPolicy.ALLOWED_DERIVED_SECTIONS,
        )
    }

    @Test
    fun everyDeclaredForbiddenCategoryRemainsExcluded() {
        assertEquals(ForbiddenExportData.entries.toSet(), ExportImportPolicy.FORBIDDEN_DATA)
        assertTrue(ForbiddenExportData.LOCAL_SOURCE_POINTERS in ExportImportPolicy.FORBIDDEN_DATA)
        assertTrue(ForbiddenExportData.DATABASE_AND_KEY_MATERIAL in ExportImportPolicy.FORBIDDEN_DATA)
        assertTrue(ForbiddenExportData.CONNECTOR_CONSENT_AND_SETTINGS in ExportImportPolicy.FORBIDDEN_DATA)
        assertTrue(ForbiddenExportData.INDEXING_QUEUE_AND_RUNTIME in ExportImportPolicy.FORBIDDEN_DATA)
    }
}

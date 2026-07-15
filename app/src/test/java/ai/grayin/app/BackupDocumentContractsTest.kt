package ai.grayin.app

import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDocumentContractsTest {
    @Test
    fun policyUsesClosedBackupTypesAndDeterministicUtcName() {
        assertEquals("application/vnd.ai.grayin.backup", BackupDocumentContractPolicy.MIME_TYPE)
        assertArrayEquals(
            arrayOf("application/vnd.ai.grayin.backup", "application/octet-stream"),
            BackupDocumentContractPolicy.acceptedMimeTypes,
        )
        assertEquals(
            "grayin-backup-2026-07-15-123456.grayin",
            BackupDocumentContractPolicy.suggestedFileName(Instant.parse("2026-07-15T12:34:56Z")),
        )
    }

    @Test
    fun suggestedNameRejectsPathsWrongExtensionsAndOversizedValues() {
        listOf(
            "../backup.grayin",
            "folder/backup.grayin",
            "backup.zip",
            "backup.grayin/other",
            "a".repeat(129) + ".grayin",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupDocumentContractPolicy.requireValidSuggestedFileName(invalid)
            }
        }
        assertEquals(
            "backup.grayin",
            BackupDocumentContractPolicy.requireValidSuggestedFileName("backup.grayin"),
        )
    }

    @Test
    fun documentContractsAreLocalOnlyOpenableAndNeverPersistUriGrants() {
        val source = File("src/main/java/ai/grayin/app/BackupDocumentContracts.kt").readText()

        assertTrue(source.contains("Intent.ACTION_CREATE_DOCUMENT"))
        assertTrue(source.contains("Intent.ACTION_OPEN_DOCUMENT"))
        assertTrue(source.contains("Intent.CATEGORY_OPENABLE"))
        assertEquals(2, source.windowed("Intent.EXTRA_LOCAL_ONLY, true".length)
            .count { it == "Intent.EXTRA_LOCAL_ONLY, true" })
        assertTrue(source.contains("ContentResolver.SCHEME_CONTENT"))
        assertFalse(source.contains("FLAG_GRANT_PERSISTABLE_URI_PERMISSION"))
        assertFalse(BackupDocumentContractPolicy.acceptedMimeTypes.any { it == "*/*" })
    }
}

package ai.grayin.app

import ai.grayin.core.ai.LocalModelStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelSettingsRowsTest {
    @Test
    fun releaseRowsDoNotAdvertiseUnsupportedAdbPath() {
        val strings = GrayinText.forOption(GrayinLanguageOption.ENGLISH)

        val releaseRows = localModelSettingsRows(LocalModelStatus.UNAVAILABLE, strings, debuggable = false)
        val debugRows = localModelSettingsRows(LocalModelStatus.UNAVAILABLE, strings, debuggable = true)

        assertFalse(releaseRows.contains(strings.localGemmaModelDevInstallGuide))
        assertTrue(debugRows.contains(strings.localGemmaModelDevInstallGuide))
    }
}

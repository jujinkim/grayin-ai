package ai.grayin.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GrayinLanguageTest {
    @Test
    fun storageKeysMatchSettingsContract() {
        assertEquals("system", GrayinLanguageOption.SYSTEM.storageKey)
        assertEquals("korean", GrayinLanguageOption.KOREAN.storageKey)
        assertEquals("english", GrayinLanguageOption.ENGLISH.storageKey)
        assertEquals("japanese", GrayinLanguageOption.JAPANESE.storageKey)
    }

    @Test
    fun explicitLanguageOptionsIgnoreSystemLanguage() {
        assertEquals(
            GrayinLanguage.KOREAN,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.KOREAN, systemLanguage = "en"),
        )
        assertEquals(
            GrayinLanguage.ENGLISH,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.ENGLISH, systemLanguage = "ko"),
        )
        assertEquals(
            GrayinLanguage.JAPANESE,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.JAPANESE, systemLanguage = "ko"),
        )
    }

    @Test
    fun systemLanguageSupportsKoreanJapaneseAndEnglishFallback() {
        assertEquals(
            GrayinLanguage.KOREAN,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.SYSTEM, systemLanguage = "ko"),
        )
        assertEquals(
            GrayinLanguage.JAPANESE,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.SYSTEM, systemLanguage = "ja"),
        )
        assertEquals(
            GrayinLanguage.ENGLISH,
            GrayinLanguageResolver.resolve(GrayinLanguageOption.SYSTEM, systemLanguage = "fr"),
        )
    }

    @Test
    fun localizedStringsCoverNavigationAndLanguageOptions() {
        assertEquals("Sources", GrayinText.forOption(GrayinLanguageOption.ENGLISH).screenLabel(GrayinScreen.Sources))
        assertEquals("설정", GrayinText.forOption(GrayinLanguageOption.KOREAN).screenLabel(GrayinScreen.Settings))
        assertEquals("日本語", GrayinText.forOption(GrayinLanguageOption.JAPANESE).languageOptionLabel(GrayinLanguageOption.JAPANESE))
        assertEquals("2件", GrayinText.forOption(GrayinLanguageOption.JAPANESE).itemCount(2))
        assertEquals(
            "HAS_LOCATION 기능을 현재 사용할 수 없습니다.",
            GrayinText.forOption(GrayinLanguageOption.KOREAN).capabilityUnavailable("HAS_LOCATION"),
        )
    }

    @Test
    fun sourcesIntroControlsInitialScreen() {
        assertEquals(GrayinScreen.Sources, initialScreenForSourcesIntro(hasSeenSourcesIntro = false))
        assertEquals(GrayinScreen.Ask, initialScreenForSourcesIntro(hasSeenSourcesIntro = true))
    }

    @Test
    fun localizedStringsCoverSourceConnectionIntro() {
        assertEquals(
            "Connect sources before asking",
            GrayinText.forOption(GrayinLanguageOption.ENGLISH).sourceConnectionTitle,
        )
        assertEquals(
            "질문 전 소스를 연결하세요",
            GrayinText.forOption(GrayinLanguageOption.KOREAN).sourceConnectionTitle,
        )
        assertEquals(
            "質問前にソースを接続",
            GrayinText.forOption(GrayinLanguageOption.JAPANESE).sourceConnectionTitle,
        )
        assertEquals(
            "Source connection is unavailable until this connector is implemented.",
            GrayinText.forOption(GrayinLanguageOption.ENGLISH).connectorConnectionUnavailable,
        )
    }

    @Test
    fun localizedStringsCoverSourceIndexingControls() {
        val korean = GrayinText.forOption(GrayinLanguageOption.KOREAN)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)

        assertEquals("지금 모두 인덱싱", korean.indexAllNow)
        assertEquals("Automatic indexing", english.automaticIndexing)
        assertEquals(
            "켜짐 · 02:00-05:00 · 충전 중일 때만",
            korean.automaticIndexingSummary(AutomaticIndexingUiState(enabled = true)),
        )
        assertEquals(
            "No connected sources are ready to index.",
            english.noSourcesReadyToIndex,
        )
    }

    @Test
    fun localizedStringsCoverConnectorNames() {
        assertEquals("Notifications", GrayinText.forOption(GrayinLanguageOption.ENGLISH).connectorName("notification", "x"))
        assertEquals("앱 사용", GrayinText.forOption(GrayinLanguageOption.KOREAN).connectorName("app_usage", "x"))
        assertEquals("fallback", GrayinText.forOption(GrayinLanguageOption.JAPANESE).connectorName("unknown", "fallback"))
    }
}

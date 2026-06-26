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
}

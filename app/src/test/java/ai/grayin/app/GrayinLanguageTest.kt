package ai.grayin.app

import ai.grayin.core.ai.ModelDownloadStatus
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.ocr.OcrLanguagePack
import ai.grayin.core.ocr.OcrLanguagePackFailureCode
import ai.grayin.core.ocr.OcrLanguagePackStatus
import ai.grayin.core.transfer.TransferFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayinLanguageTest {
    @Test
    fun `backup actions and every stable transfer failure are localized`() {
        GrayinLanguageOption.entries.map(GrayinText::forOption).forEach { strings ->
            assertTrue(strings.backupTitle().isNotBlank())
            assertTrue(strings.backupDisclosure().isNotBlank())
            assertTrue(strings.backupImportWarningBody().isNotBlank())
            TransferFailureCode.entries.forEach { code ->
                assertTrue(strings.backupFailure(code).isNotBlank())
            }
        }
    }

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
        assertTrue(english.localDocumentSupportDisclosure().contains("PDF"))
        assertTrue(korean.localDocumentRevokeAllAction().contains("모든"))
    }

    @Test
    fun localDocumentPickerUsesOnlySupportedFamilies() {
        assertEquals(
            setOf("text/plain", "text/markdown", "application/pdf", "application/octet-stream"),
            LocalDocumentPickerContract.mimeTypes().toSet(),
        )
    }

    @Test
    fun localizedStringsCoverLocalModelImportControls() {
        val korean = GrayinText.forOption(GrayinLanguageOption.KOREAN)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)

        assertEquals("Import local Gemma model", english.importLocalGemmaModel)
        assertEquals("Delete imported Gemma model", english.deleteLocalGemmaModel)
        assertEquals("Open model download page", english.openLocalGemmaModelDownloadPage)
        assertEquals("로컬 Gemma 모델 가져오기", korean.importLocalGemmaModel)
        assertEquals("가져온 Gemma 모델 삭제", korean.deleteLocalGemmaModel)
        assertEquals("모델 다운로드 페이지 열기", korean.openLocalGemmaModelDownloadPage)
        assertEquals("Select a .litertlm model file larger than 1 MB.", english.localGemmaModelInvalidFile)
        assertEquals(
            "Download page: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            english.localGemmaModelDownloadUrl,
        )
    }

    @Test
    fun localizedStringsCoverRuntimeModelCatalogControls() {
        val korean = GrayinText.forOption(GrayinLanguageOption.KOREAN)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)

        assertEquals("Local model", english.localModelCatalogTitle)
        assertEquals("Selected", english.localModelSelectedBadge)
        assertEquals("Download", english.localModelDownload)
        assertEquals("License: Apache-2.0", english.localModelLicense("Apache-2.0"))
        assertEquals("Status: download required", english.localModelStatus(ModelDownloadStatus.NOT_DOWNLOADED))
        assertEquals("Queued Gemma 4 E2B download. It runs on Wi-Fi.", english.localModelDownloadQueued("Gemma 4 E2B"))
        assertEquals("로컬 모델", korean.localModelCatalogTitle)
        assertEquals("선택됨", korean.localModelSelectedBadge)
        assertEquals("다운로드", korean.localModelDownload)
        assertEquals("상태: 준비됨", korean.localModelStatus(ModelDownloadStatus.READY))
    }

    @Test
    fun localizedStringsCoverConnectorNames() {
        assertEquals("Notifications", GrayinText.forOption(GrayinLanguageOption.ENGLISH).connectorName("notification", "x"))
        assertEquals("앱 사용", GrayinText.forOption(GrayinLanguageOption.KOREAN).connectorName("app_usage", "x"))
        assertEquals("fallback", GrayinText.forOption(GrayinLanguageOption.JAPANESE).connectorName("unknown", "fallback"))
    }

    @Test
    fun everyConnectorScanIssueHasEnglishKoreanAndJapaneseCopy() {
        val strings = listOf(
            GrayinText.forOption(GrayinLanguageOption.ENGLISH),
            GrayinText.forOption(GrayinLanguageOption.KOREAN),
            GrayinText.forOption(GrayinLanguageOption.JAPANESE),
        )

        ConnectorScanIssueCode.entries.forEach { issueCode ->
            strings.forEach { localized ->
                assertTrue(localized.connectorScanIssue(issueCode).isNotBlank())
            }
        }
    }

    @Test
    fun everyOcrPackStateFailureAndActionHasLocalizedCopy() {
        val strings = listOf(
            GrayinText.forOption(GrayinLanguageOption.ENGLISH),
            GrayinText.forOption(GrayinLanguageOption.KOREAN),
            GrayinText.forOption(GrayinLanguageOption.JAPANESE),
        )

        strings.forEach { localized ->
            val commonRows = listOf(
                localized.ocrLanguageDataTitle(),
                localized.ocrLanguageDataDisclosure(),
                localized.ocrLanguagePackSize("1.00 MB"),
                localized.ocrLanguagePackLicense("Apache-2.0"),
                localized.ocrLanguagePackCatalogCommit("abcdef"),
                localized.ocrLanguagePackRequiresUnmeteredNetwork(),
                localized.ocrLanguagePackProgress(42),
                localized.ocrLanguagePackDownloadAction(),
                localized.ocrLanguagePackCancelAction(),
                localized.ocrLanguagePackDeleteAction(),
                localized.ocrLanguagePackQueued("English"),
                localized.ocrLanguagePackCanceled("English"),
                localized.ocrLanguagePackDeleted("English"),
                localized.ocrLanguagePackActionFailed(),
            )
            commonRows.forEach { row -> assertTrue(row.isNotBlank()) }
            OcrLanguagePack.entries.forEach { pack ->
                assertTrue(localized.ocrLanguagePackName(pack).isNotBlank())
            }
            OcrLanguagePackStatus.entries.forEach { status ->
                assertTrue(localized.ocrLanguagePackStatus(status).isNotBlank())
            }
            OcrLanguagePackFailureCode.entries.forEach { failure ->
                val copy = localized.ocrLanguagePackFailure(failure)
                assertTrue(copy.isNotBlank())
                assertFalse(copy.contains(failure.name))
            }
        }
    }
}

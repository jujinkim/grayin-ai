package ai.grayin.connectors.localfiles.document

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRuntimeContractTest {
    @Test
    fun boundedCompleteResultPassesIndependentValidation() {
        val result = DocumentProcessingResult(
            outcomeCode = DocumentRuntimeWire.OUTCOME_COMPLETE,
            totalPageCount = 1,
            processedPageCount = 1,
            pageSignals = listOf(
                DocumentPageSignal(
                    pageNumber = 1,
                    extractionModeCode = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
                    nonBlankLineCount = 3,
                    keywordSignals = listOf("planning", "meeting"),
                    confidenceCode = DocumentRuntimeWire.CONFIDENCE_MEDIUM,
                ),
            ),
            issueCodes = intArrayOf(),
        )

        assertTrue(DocumentProcessingResultValidator.isValid(result))
    }

    @Test
    fun malformedOrOversizedResultIsRejected() {
        val duplicatePages = validPartialResult().copy(
            pageSignals = listOf(validSignal(1), validSignal(1)),
        )
        val unknownIssue = validPartialResult().copy(issueCodes = intArrayOf(Int.MAX_VALUE))
        val moreSignalsThanProcessedPages = validPartialResult().copy(
            processedPageCount = 0,
        )
        val signalBeyondProcessedPage = validPartialResult().copy(
            totalPageCount = 64,
            processedPageCount = 1,
            pageSignals = listOf(validSignal(64)),
        )
        val failedWithDocumentCounts = DocumentProcessingResult.failed(
            DocumentRuntimeWire.ISSUE_PDF_MALFORMED,
        ).copy(totalPageCount = 1, processedPageCount = 1)
        val oversizedKeywords = validPartialResult().copy(
            pageSignals = listOf(
                validSignal(1).copy(
                    keywordSignals = (1..DocumentSignalDeriver.MAX_KEYWORDS + 1).map { "keyword$it" },
                ),
            ),
        )

        assertFalse(DocumentProcessingResultValidator.isValid(duplicatePages))
        assertFalse(DocumentProcessingResultValidator.isValid(unknownIssue))
        assertFalse(DocumentProcessingResultValidator.isValid(moreSignalsThanProcessedPages))
        assertFalse(DocumentProcessingResultValidator.isValid(signalBeyondProcessedPage))
        assertFalse(DocumentProcessingResultValidator.isValid(failedWithDocumentCounts))
        assertFalse(DocumentProcessingResultValidator.isValid(oversizedKeywords))
    }

    @Test
    fun wireIssueCodesMapOnlyToClosedConnectorIssueEnums() {
        assertEquals(
            DocumentRuntimeWire.validIssueCodes.size,
            DocumentRuntimeWire.validIssueCodes.mapNotNull(DocumentRuntimeWire::issueCode).distinct().size,
        )
        assertEquals(null, DocumentRuntimeWire.issueCode(Int.MAX_VALUE))
    }

    @Test
    fun binderResultDtoHasNoRawOrSourceIdentityFields() {
        val forbiddenNames = listOf(
            "raw",
            "text",
            "uri",
            "path",
            "file",
            "name",
            "bitmap",
            "bytes",
            "content",
            "exception",
            "message",
        )
        val fields = listOf(DocumentProcessingResult::class.java, DocumentPageSignal::class.java)
            .flatMap { type -> type.declaredFields.toList() }
            .filterNot { field -> field.isSynthetic || field.name == "CREATOR" || field.name == "\$stable" }

        fields.forEach { field ->
            forbiddenNames.forEach { forbidden ->
                assertFalse(
                    "Binder DTO field is forbidden: ${field.declaringClass.simpleName}.${field.name}",
                    field.name.contains(forbidden, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun manifestKeepsDocumentServicePrivateAndInSiblingProcess() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val serviceBlock = manifest.substringAfter(
            "android:name=\".connectors.localfiles.document.DocumentProcessingService\"",
        ).substringBefore("/>")

        assertTrue(serviceBlock.contains("android:exported=\"false\""))
        assertTrue(serviceBlock.contains("android:process=\":document\""))
        assertFalse(serviceBlock.contains("isolatedProcess"))
        assertFalse(serviceBlock.contains("intent-filter"))
    }

    @Test
    fun documentRuntimePackageHasNoNetworkLoggingOrTemporaryFileCalls() {
        val sources = File("src/main/java/ai/grayin/connectors/localfiles/document")
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.extension == "kt" }
            .toList()
        val forbiddenTokens = listOf(
            "URL(",
            "openConnection(",
            "HttpURLConnection",
            "Socket(",
            "Log.",
            "println(",
            "createTempFile(",
            "cacheDir",
            "FileOutputStream(",
            "OcrLanguagePackDownloadScheduler",
            "getHOCRText(",
            "getWords(",
        )

        sources.forEach { file ->
            val source = file.readText()
            forbiddenTokens.forEach { token ->
                assertFalse(
                    "Document runtime must not use $token in ${file.name}",
                    source.contains(token),
                )
            }
        }
    }

    private fun validPartialResult(): DocumentProcessingResult = DocumentProcessingResult(
        outcomeCode = DocumentRuntimeWire.OUTCOME_PARTIAL,
        totalPageCount = 2,
        processedPageCount = 2,
        pageSignals = listOf(validSignal(1)),
        issueCodes = intArrayOf(
            DocumentRuntimeWire.ISSUE_OCR_MODEL_UNAVAILABLE,
            DocumentRuntimeWire.ISSUE_PARTIAL_DOCUMENT_INDEX,
        ),
    )

    private fun validSignal(pageNumber: Int): DocumentPageSignal = DocumentPageSignal(
        pageNumber = pageNumber,
        extractionModeCode = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
        nonBlankLineCount = 1,
        keywordSignals = listOf("planning"),
        confidenceCode = DocumentRuntimeWire.CONFIDENCE_MEDIUM,
    )
}

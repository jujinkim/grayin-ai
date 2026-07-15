package ai.grayin.connectors.localfiles.document

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentProcessingServiceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun privateProcessReturnsOnlyTypedFailureForMalformedPdf() = runBlocking {
        val file = File(context.cacheDir, "document-service-malformed.pdf").apply { delete() }
        FileOutputStream(file).use { output ->
            output.write("%PDF-1.7\nnot-a-complete-document".toByteArray())
        }

        val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            BoundDocumentProcessingClient(context).process(descriptor)
        }

        assertTrue(DocumentProcessingResultValidator.isValid(result))
        assertEquals(DocumentRuntimeWire.OUTCOME_FAILED, result.outcomeCode)
        assertEquals(
            listOf(DocumentRuntimeWire.ISSUE_PDF_MALFORMED),
            result.issueCodes.toList(),
        )
        assertTrue(result.pageSignals.isEmpty())
    }
}

package ai.grayin.connectors.localfiles.document

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPdfDescriptorValidatorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun acceptsSeekableBoundedDescriptorWithPdfSignature() {
        val file = testFile("valid.pdf")
        FileOutputStream(file).use { output -> output.write("%PDF-1.7\n%%EOF\n".toByteArray()) }

        val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            AndroidPdfDescriptorValidator.validate(descriptor)
        }

        assertTrue(result is PdfDescriptorValidation.Valid)
        assertEquals(file.length(), (result as PdfDescriptorValidation.Valid).sizeBytes)
    }

    @Test
    fun rejectsLyingExtensionWhenSignatureIsNotPdf() {
        val file = testFile("lying.pdf")
        FileOutputStream(file).use { output -> output.write("<html>not a PDF</html>".toByteArray()) }

        val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            AndroidPdfDescriptorValidator.validate(descriptor)
        }

        assertEquals(
            DocumentRuntimeWire.ISSUE_PDF_MALFORMED,
            (result as PdfDescriptorValidation.Rejected).issueCode,
        )
    }

    @Test
    fun rejectsPipeBeforeTrustingUnknownProviderSize() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val result = AndroidPdfDescriptorValidator.validate(pipe[0])

            assertEquals(
                DocumentRuntimeWire.ISSUE_DOCUMENT_NOT_SEEKABLE,
                (result as PdfDescriptorValidation.Rejected).issueCode,
            )
        } finally {
            pipe.forEach { descriptor -> descriptor.close() }
        }
    }

    @Test
    fun rejectsOversizedDescriptorBeforePdfParsing() {
        val file = testFile("oversized.pdf")
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.write("%PDF-1.7\n".toByteArray())
            randomAccess.setLength(PdfResourceLimits.MAX_PDF_BYTES + 1L)
        }

        val result = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            AndroidPdfDescriptorValidator.validate(descriptor)
        }

        assertEquals(
            DocumentRuntimeWire.ISSUE_DOCUMENT_FILE_TOO_LARGE,
            (result as PdfDescriptorValidation.Rejected).issueCode,
        )
    }

    private fun testFile(name: String): File {
        val directory = File(context.cacheDir, "document-validator-test").apply { mkdirs() }
        return File(directory, name).apply { delete() }
    }
}

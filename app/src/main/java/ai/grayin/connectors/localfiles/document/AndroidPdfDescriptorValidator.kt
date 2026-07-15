package ai.grayin.connectors.localfiles.document

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

sealed interface PdfDescriptorValidation {
    data class Valid(val sizeBytes: Long) : PdfDescriptorValidation

    data class Rejected(val issueCode: Int) : PdfDescriptorValidation
}

object AndroidPdfDescriptorValidator {
    fun validate(descriptor: ParcelFileDescriptor): PdfDescriptorValidation {
        if (!isSeekable(descriptor)) {
            return PdfDescriptorValidation.Rejected(DocumentRuntimeWire.ISSUE_DOCUMENT_NOT_SEEKABLE)
        }
        val sizeBytes = runCatching { descriptor.statSize }.getOrDefault(-1L)
        if (sizeBytes <= 0L) {
            return PdfDescriptorValidation.Rejected(DocumentRuntimeWire.ISSUE_DOCUMENT_SIZE_UNKNOWN)
        }
        if (sizeBytes > PdfResourceLimits.MAX_PDF_BYTES) {
            return PdfDescriptorValidation.Rejected(DocumentRuntimeWire.ISSUE_DOCUMENT_FILE_TOO_LARGE)
        }
        if (!hasPdfSignature(descriptor)) {
            return PdfDescriptorValidation.Rejected(DocumentRuntimeWire.ISSUE_PDF_MALFORMED)
        }
        return PdfDescriptorValidation.Valid(sizeBytes)
    }

    private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean {
        return try {
            val position = Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            Os.lseek(descriptor.fileDescriptor, position, OsConstants.SEEK_SET)
            true
        } catch (_: ErrnoException) {
            false
        }
    }

    private fun hasPdfSignature(descriptor: ParcelFileDescriptor): Boolean {
        val signature = ByteArray(PDF_SIGNATURE.size)
        return try {
            val read = Os.pread(
                descriptor.fileDescriptor,
                signature,
                0,
                signature.size,
                0L,
            )
            read == signature.size && signature.contentEquals(PDF_SIGNATURE)
        } catch (_: ErrnoException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
}

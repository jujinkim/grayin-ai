package ai.grayin.core.artifact

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class FixedEndpointDocumentSpec(
    val id: String,
    override val url: String,
    val maximumSizeBytes: Int,
    val allowedContentTypes: Set<String>,
    override val acceptContentType: String,
) : FixedHttpsResourceSpec {
    init {
        require(id.matches(SAFE_ID)) { "Document ID is invalid." }
        require(maximumSizeBytes in 1..MAXIMUM_ALLOWED_BYTES) { "Document size bound is invalid." }
        require(allowedContentTypes.isNotEmpty()) { "Document content types must not be empty." }
        require(acceptContentType in allowedContentTypes) { "Document Accept type must be allowed." }
        val uri = URI(url)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.query == null &&
                uri.fragment == null,
        ) { "Document URL must be a fixed HTTPS origin and path." }
    }

    private companion object {
        const val MAXIMUM_ALLOWED_BYTES = 1024 * 1024
        val SAFE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

internal sealed interface BoundedDocumentDownloadResult {
    data class Available(val bytes: ByteArray) : BoundedDocumentDownloadResult

    data class Failed(
        val code: ArtifactDownloadFailureCode,
        val retryable: Boolean = false,
    ) : BoundedDocumentDownloadResult
}

internal class BoundedFixedEndpointDownloader(
    private val httpClient: ArtifactHttpClient = HttpsArtifactHttpClient(),
) {
    suspend fun fetch(document: FixedEndpointDocumentSpec): BoundedDocumentDownloadResult {
        val response = try {
            httpClient.open(document)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return failed(ArtifactDownloadFailureCode.IO_FAILURE, retryable = true)
        }

        try {
            when (response.statusCode) {
                in 300..399 -> return failed(ArtifactDownloadFailureCode.REDIRECT_REJECTED)
                in 500..599 -> return failed(ArtifactDownloadFailureCode.SERVER_ERROR, retryable = true)
                200 -> Unit
                else -> return failed(ArtifactDownloadFailureCode.HTTP_REJECTED)
            }
            val contentType = response.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (contentType !in document.allowedContentTypes) {
                return failed(ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID)
            }
            val contentEncoding = response.contentEncoding?.trim()?.lowercase()
            if (contentEncoding != null && contentEncoding != "identity") {
                return failed(ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID)
            }
            val declaredLength = response.contentLength
            if (
                declaredLength != null &&
                (declaredLength < 0L || declaredLength > document.maximumSizeBytes.toLong())
            ) {
                return failed(ArtifactDownloadFailureCode.SIZE_MISMATCH)
            }

            val output = ByteArrayOutputStream(
                declaredLength?.toInt()
                    ?: DEFAULT_INITIAL_CAPACITY,
            )
            response.openBody().use { source ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > document.maximumSizeBytes) {
                        return failed(ArtifactDownloadFailureCode.SIZE_MISMATCH)
                    }
                    output.write(buffer, 0, read)
                }
            }
            return BoundedDocumentDownloadResult.Available(output.toByteArray())
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return failed(ArtifactDownloadFailureCode.IO_FAILURE, retryable = true)
        } finally {
            response.close()
        }
    }

    private fun failed(
        code: ArtifactDownloadFailureCode,
        retryable: Boolean = false,
    ): BoundedDocumentDownloadResult.Failed {
        return BoundedDocumentDownloadResult.Failed(code, retryable)
    }

    private companion object {
        const val DEFAULT_INITIAL_CAPACITY = 8 * 1024
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}

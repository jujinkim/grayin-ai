package ai.grayin.core.artifact

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class FixedCatalogArtifactSpec(
    val id: String,
    val url: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val sha256: String,
    val allowedContentTypes: Set<String> = setOf("application/octet-stream"),
) {
    init {
        require(id.matches(SAFE_ID)) { "Artifact ID is invalid." }
        require(fileName.matches(SAFE_FILE_NAME) && '/' !in fileName && ".." !in fileName) {
            "Artifact file name is invalid."
        }
        require(expectedSizeBytes > 0L) { "Artifact size must be positive." }
        require(sha256.matches(SHA_256)) { "Artifact SHA-256 must be lowercase hexadecimal." }
        require(allowedContentTypes.isNotEmpty()) { "Artifact content types must not be empty." }
        val uri = URI(url)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.query == null &&
                uri.fragment == null,
        ) { "Artifact URL must be a fixed HTTPS origin and path." }
    }

    private companion object {
        val SAFE_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

internal enum class ArtifactDownloadFailureCode {
    REDIRECT_REJECTED,
    HTTP_REJECTED,
    SERVER_ERROR,
    CONTENT_TYPE_INVALID,
    CONTENT_ENCODING_INVALID,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    IO_FAILURE,
}

internal sealed interface ArtifactDownloadResult {
    data class Verified(
        val bytes: Long,
        val sha256: String,
    ) : ArtifactDownloadResult

    data class Failed(
        val code: ArtifactDownloadFailureCode,
        val retryable: Boolean = false,
    ) : ArtifactDownloadResult
}

internal interface ArtifactHttpResponse : Closeable {
    val statusCode: Int
    val contentLength: Long?
    val contentType: String?
    val contentEncoding: String?

    fun openBody(): InputStream
}

internal fun interface ArtifactHttpClient {
    @Throws(IOException::class)
    fun open(artifact: FixedCatalogArtifactSpec): ArtifactHttpResponse
}

internal fun interface HttpsConnectionFactory {
    fun open(url: URL): HttpsURLConnection
}

internal class HttpsArtifactHttpClient(
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { url ->
        url.openConnection() as HttpsURLConnection
    },
) : ArtifactHttpClient {
    override fun open(artifact: FixedCatalogArtifactSpec): ArtifactHttpResponse {
        val connection = connectionFactory.open(URL(artifact.url)).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "GrayinAI/0.1")
        }
        return try {
            connection.connect()
            HttpsArtifactHttpResponse(connection)
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private class HttpsArtifactHttpResponse(
        private val connection: HttpsURLConnection,
    ) : ArtifactHttpResponse {
        override val statusCode: Int
            get() = connection.responseCode
        override val contentLength: Long?
            get() = connection.contentLengthLong.takeIf { it >= 0L }
        override val contentType: String?
            get() = connection.contentType
        override val contentEncoding: String?
            get() = connection.contentEncoding

        override fun openBody(): InputStream = connection.inputStream

        override fun close() {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

internal class FixedCatalogArtifactDownloader(
    private val httpClient: ArtifactHttpClient = HttpsArtifactHttpClient(),
) {
    suspend fun downloadToPart(
        artifact: FixedCatalogArtifactSpec,
        partFile: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ArtifactDownloadResult {
        val parent = partFile.parentFile
            ?: return ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.IO_FAILURE)
        if ((!parent.isDirectory && !parent.mkdirs()) || (partFile.exists() && !partFile.delete())) {
            return ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.IO_FAILURE)
        }

        val response = try {
            httpClient.open(artifact)
        } catch (error: CancellationException) {
            partFile.delete()
            throw error
        } catch (_: IOException) {
            partFile.delete()
            return ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.IO_FAILURE, retryable = true)
        }

        try {
            when (response.statusCode) {
                in 300..399 -> return failed(partFile, ArtifactDownloadFailureCode.REDIRECT_REJECTED)
                in 500..599 -> return failed(partFile, ArtifactDownloadFailureCode.SERVER_ERROR, retryable = true)
                200 -> Unit
                else -> return failed(partFile, ArtifactDownloadFailureCode.HTTP_REJECTED)
            }
            val contentType = response.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (contentType !in artifact.allowedContentTypes) {
                return failed(partFile, ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID)
            }
            val contentEncoding = response.contentEncoding?.trim()?.lowercase()
            if (contentEncoding != null && contentEncoding != "identity") {
                return failed(partFile, ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID)
            }
            if (response.contentLength?.let { it != artifact.expectedSizeBytes } == true) {
                return failed(partFile, ArtifactDownloadFailureCode.SIZE_MISMATCH)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            response.openBody().use { source ->
                FileOutputStream(partFile).use { target ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        copiedBytes += read
                        if (copiedBytes > artifact.expectedSizeBytes) {
                            return failed(partFile, ArtifactDownloadFailureCode.SIZE_MISMATCH)
                        }
                        target.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onProgress(copiedBytes, artifact.expectedSizeBytes)
                    }
                    target.fd.sync()
                }
            }
            if (copiedBytes != artifact.expectedSizeBytes) {
                return failed(partFile, ArtifactDownloadFailureCode.SIZE_MISMATCH)
            }
            val actualSha256 = digest.digest().toHex()
            if (actualSha256 != artifact.sha256) {
                return failed(partFile, ArtifactDownloadFailureCode.CHECKSUM_MISMATCH)
            }
            return ArtifactDownloadResult.Verified(copiedBytes, actualSha256)
        } catch (error: CancellationException) {
            partFile.delete()
            throw error
        } catch (_: IOException) {
            return failed(partFile, ArtifactDownloadFailureCode.IO_FAILURE, retryable = true)
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        } finally {
            response.close()
        }
    }

    private fun failed(
        partFile: File,
        code: ArtifactDownloadFailureCode,
        retryable: Boolean = false,
    ): ArtifactDownloadResult.Failed {
        partFile.delete()
        return ArtifactDownloadResult.Failed(code, retryable)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

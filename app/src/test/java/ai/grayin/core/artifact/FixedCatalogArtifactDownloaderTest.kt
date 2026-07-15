package ai.grayin.core.artifact

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixedCatalogArtifactDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun specRejectsNonFixedUrlsAndUnsafeMetadata() {
        val bytes = "expected".toByteArray()
        val validSha = sha256(bytes)
        val invalidSpecs = listOf<() -> FixedCatalogArtifactSpec>(
            { FixedCatalogArtifactSpec("id", "http://example.test/a", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://user@example.test/a", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test:8443/a", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test/a?q=1", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test/a#fragment", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("../id", "https://example.test/a", "a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test/a", "../a.bin", 1L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test/a", "a.bin", 0L, validSha) },
            { FixedCatalogArtifactSpec("id", "https://example.test/a", "a.bin", 1L, "invalid") },
        )

        invalidSpecs.forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun httpsTransportDisablesRedirectFollowingBeforeConnecting() {
        val bytes = "expected".toByteArray()
        val connection = FakeHttpsConnection(URL("https://artifacts.example.test/fixed/artifact.bin"))
        val response = HttpsArtifactHttpClient(HttpsConnectionFactory { connection }).open(spec(bytes))

        assertTrue(connection.connectCalled)
        assertFalse(connection.followRedirectsAtConnect)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("GET", connection.requestMethod)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals(302, response.statusCode)

        response.close()
        assertTrue(connection.disconnected)
        assertFalse(connection.bodyOpened)
    }

    @Test
    fun exactSizeAndDigestProducesVerifiedPart() = runBlocking {
        val bytes = "verified artifact".toByteArray()
        val response = FakeResponse(
            bodyBytes = bytes,
            contentLength = bytes.size.toLong(),
            contentType = "application/octet-stream; charset=binary",
        )
        val part = File(temporaryFolder.newFolder("success"), "artifact.part")
        part.writeText("stale")
        val progress = mutableListOf<Long>()

        val result = FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
            artifact = spec(bytes),
            partFile = part,
            onProgress = { downloaded, _ -> progress += downloaded },
        )

        assertEquals(ArtifactDownloadResult.Verified(bytes.size.toLong(), sha256(bytes)), result)
        assertTrue(part.isFile)
        assertTrue(bytes.contentEquals(part.readBytes()))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(response.closed)
        assertTrue(response.bodyOpened)
    }

    @Test
    fun redirectsAreRejectedWithoutReadingTheirBody() = runBlocking {
        listOf(300, 301, 302, 303, 305, 307, 308).forEach { status ->
            val response = FakeResponse(statusCode = status, bodyBytes = "redirect".toByteArray())
            val part = File(temporaryFolder.root, "redirect-$status.part")

            val result = FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
                artifact = spec("expected".toByteArray()),
                partFile = part,
            )

            assertEquals(
                ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.REDIRECT_REJECTED),
                result,
            )
            assertFalse(response.bodyOpened)
            assertFalse(part.exists())
        }
    }

    @Test
    fun invalidHeadersAndBodiesFailClosedAndDeletePart() = runBlocking {
        val expected = "expected".toByteArray()
        val cases = listOf(
            FakeResponse(contentLength = expected.size.toLong() + 1L, bodyBytes = expected) to
                ArtifactDownloadFailureCode.SIZE_MISMATCH,
            FakeResponse(contentType = "text/html", bodyBytes = expected) to
                ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID,
            FakeResponse(contentEncoding = "gzip", bodyBytes = expected) to
                ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID,
            FakeResponse(contentLength = null, bodyBytes = expected.copyOf(expected.size - 1)) to
                ArtifactDownloadFailureCode.SIZE_MISMATCH,
            FakeResponse(contentLength = null, bodyBytes = expected + byteArrayOf(0x01)) to
                ArtifactDownloadFailureCode.SIZE_MISMATCH,
            FakeResponse(bodyBytes = expected.copyOf().also { it[0] = 0x01 }) to
                ArtifactDownloadFailureCode.CHECKSUM_MISMATCH,
        )
        cases.forEachIndexed { index, (response, expectedFailure) ->
            val part = File(temporaryFolder.root, "invalid-$index.part")

            val result = FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
                artifact = spec(expected),
                partFile = part,
            )

            assertEquals(ArtifactDownloadResult.Failed(expectedFailure), result)
            assertFalse(part.exists())
            assertTrue(response.closed)
        }
    }

    @Test
    fun serverErrorsAreRetryableAndOtherHttpErrorsArePermanent() = runBlocking {
        val expected = "expected".toByteArray()
        val server = FakeResponse(statusCode = 503, bodyBytes = byteArrayOf())
        val denied = FakeResponse(statusCode = 403, bodyBytes = byteArrayOf())

        val serverResult = FixedCatalogArtifactDownloader(ArtifactHttpClient { server }).downloadToPart(
            spec(expected),
            File(temporaryFolder.root, "server.part"),
        )
        val deniedResult = FixedCatalogArtifactDownloader(ArtifactHttpClient { denied }).downloadToPart(
            spec(expected),
            File(temporaryFolder.root, "denied.part"),
        )

        assertEquals(
            ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.SERVER_ERROR, retryable = true),
            serverResult,
        )
        assertEquals(
            ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.HTTP_REJECTED),
            deniedResult,
        )
        assertFalse(server.bodyOpened)
        assertFalse(denied.bodyOpened)
    }

    @Test
    fun transportAndBodyIoFailuresAreRetryableAndLeaveNoPart() = runBlocking {
        val expected = "expected".toByteArray()
        val openPart = File(temporaryFolder.root, "open-io.part")
        val openFailure = FixedCatalogArtifactDownloader(
            ArtifactHttpClient { throw IOException("transport detail") },
        ).downloadToPart(spec(expected), openPart)
        assertEquals(
            ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.IO_FAILURE, retryable = true),
            openFailure,
        )
        assertFalse(openPart.exists())

        val response = FakeResponse(
            body = object : InputStream() {
                override fun read(): Int = throw IOException("body detail")
            },
            contentLength = expected.size.toLong(),
        )
        val bodyPart = File(temporaryFolder.root, "body-io.part")
        val bodyFailure = FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
            spec(expected),
            bodyPart,
        )
        assertEquals(
            ArtifactDownloadResult.Failed(ArtifactDownloadFailureCode.IO_FAILURE, retryable = true),
            bodyFailure,
        )
        assertFalse(bodyPart.exists())
        assertTrue(response.closed)
    }

    @Test
    fun cancellationIsRethrownAndClosesAndDeletesThePart() {
        val expected = "expected".toByteArray()
        val response = FakeResponse(
            body = object : InputStream() {
                override fun read(): Int = throw CancellationException("cancel")
            },
            contentLength = expected.size.toLong(),
        )
        val part = File(temporaryFolder.root, "canceled.part")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
                    spec(expected),
                    part,
                )
            }
        }

        assertFalse(part.exists())
        assertTrue(response.closed)
    }

    @Test
    fun progressCallbackFailureIsRethrownAndCleansUp() {
        val expected = "expected".toByteArray()
        val response = FakeResponse(bodyBytes = expected, contentLength = expected.size.toLong())
        val part = File(temporaryFolder.root, "callback-failure.part")

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                FixedCatalogArtifactDownloader(ArtifactHttpClient { response }).downloadToPart(
                    artifact = spec(expected),
                    partFile = part,
                    onProgress = { _, _ -> error("callback detail") },
                )
            }
        }

        assertFalse(part.exists())
        assertTrue(response.closed)
    }

    private fun spec(bytes: ByteArray): FixedCatalogArtifactSpec {
        return FixedCatalogArtifactSpec(
            id = "test-artifact",
            url = "https://artifacts.example.test/fixed/artifact.bin",
            fileName = "artifact.bin",
            expectedSizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class FakeResponse(
        override val statusCode: Int = 200,
        override val contentLength: Long? = null,
        override val contentType: String? = "application/octet-stream",
        override val contentEncoding: String? = null,
        bodyBytes: ByteArray = byteArrayOf(),
        private val body: InputStream = ByteArrayInputStream(bodyBytes),
    ) : ArtifactHttpResponse {
        var bodyOpened = false
        var closed = false

        override fun openBody(): InputStream {
            bodyOpened = true
            return body
        }

        override fun close() {
            closed = true
            body.close()
        }
    }

    private class FakeHttpsConnection(url: URL) : HttpsURLConnection(url) {
        var connectCalled = false
        var followRedirectsAtConnect = true
        var disconnected = false
        var bodyOpened = false

        override fun connect() {
            connectCalled = true
            followRedirectsAtConnect = instanceFollowRedirects
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getCipherSuite(): String = "TLS_FAKE"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        override fun getResponseCode(): Int = 302

        override fun getContentLengthLong(): Long = 0L

        override fun getContentType(): String = "text/html"

        override fun getInputStream(): InputStream {
            bodyOpened = true
            return ByteArrayInputStream(byteArrayOf())
        }
    }
}

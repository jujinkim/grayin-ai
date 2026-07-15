package ai.grayin.core.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayinTransferPayloadCodecTest {
    private val codec = GrayinTransferPayloadCodec()

    @Test
    fun `validator accepts a detached valid snapshot`() {
        val payload = TransferTestFixtures.payload()

        TransferSnapshotValidator.validate(
            payload.copy(snapshot = TransferSnapshotValidator.detached(payload.snapshot)),
        )
    }

    @Test
    fun `round trip preserves all seven sections and detaches pointers`() {
        val original = TransferTestFixtures.payload()

        val encoded = success(codec.encode(original))
        val encodedText = encoded.toString(Charsets.UTF_8)
        val decoded = success(codec.decode(encoded))

        assertFalse(encodedText.contains("localPointer"))
        assertFalse(encodedText.contains("content://"))
        assertFalse(encodedText.contains("location-provider:"))
        assertEquals(
            original.copy(snapshot = TransferSnapshotValidator.detached(original.snapshot)),
            decoded,
        )
        assertEquals(6, decoded.snapshot.sourceReferences.size)
        assertEquals(6, decoded.snapshot.derivedMemoryEvents.size)
        assertEquals(6, decoded.snapshot.citations.size)
        assertEquals(1, decoded.snapshot.dailySummaries.size)
        assertEquals(1, decoded.snapshot.placeClusters.size)
        assertEquals(1, decoded.snapshot.appUsageSummaries.size)
        assertEquals(6, decoded.snapshot.connectorScanStatuses.size)
        assertTrue(decoded.snapshot.sourceReferences.all { source -> source.localPointer == null })
        assertEquals(
            setOf("location", "photos", "calendar", "notification", "app_usage", "local_files"),
            decoded.snapshot.connectorScanStatuses.mapTo(mutableSetOf()) { status -> status.connectorId },
        )
    }

    @Test
    fun `encoding is deterministic across section input order`() {
        val payload = TransferTestFixtures.payload()
        val reversed = payload.copy(
            snapshot = payload.snapshot.copy(
                sourceReferences = payload.snapshot.sourceReferences.reversed(),
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.reversed(),
                citations = payload.snapshot.citations.reversed(),
                dailySummaries = payload.snapshot.dailySummaries.reversed(),
                placeClusters = payload.snapshot.placeClusters.reversed(),
                appUsageSummaries = payload.snapshot.appUsageSummaries.reversed(),
                connectorScanStatuses = payload.snapshot.connectorScanStatuses.reversed(),
            ),
        )

        assertArrayEquals(
            success(codec.encode(payload)),
            success(codec.encode(reversed)),
        )
    }

    @Test
    fun `strict codec rejects unknown object keys`() {
        val encoded = success(codec.encode(TransferTestFixtures.payload()))
        val modified = encoded.toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"unknown\":0,")
            .toByteArray(Charsets.UTF_8)

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(modified))
    }

    @Test
    fun `strict codec rejects duplicate object keys including escaped aliases`() {
        val encoded = success(codec.encode(TransferTestFixtures.payload()))
        val modified = encoded.toString(Charsets.UTF_8)
            .replaceFirst(
                "\"payloadVersion\":1",
                "\"payloadVersion\":1,\"payload\\u0056ersion\":1",
            )
            .toByteArray(Charsets.UTF_8)

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(modified))
    }

    @Test
    fun `strict codec rejects malformed UTF-8`() {
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(malformed))
    }

    @Test
    fun `strict codec rejects an unknown enum`() {
        val encoded = success(codec.encode(TransferTestFixtures.payload()))
        val modified = encoded.toString(Charsets.UTF_8)
            .replaceFirst("\"sourceKind\":\"LOCATION\"", "\"sourceKind\":\"UNKNOWN_KIND\"")
            .toByteArray(Charsets.UTF_8)

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(modified))
    }

    @Test
    fun `validator rejects a dangling source reference before encryption`() {
        val payload = TransferTestFixtures.payload()
        val invalidEvent = payload.snapshot.derivedMemoryEvents.first().copy(
            sourceReferenceIds = listOf("source:location:missing"),
        )
        val invalid = payload.copy(
            snapshot = payload.snapshot.copy(
                derivedMemoryEvents = listOf(invalidEvent) + payload.snapshot.derivedMemoryEvents.drop(1),
            ),
        )

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(invalid))
    }

    @Test
    fun `validator rejects non-canonical Local Files output`() {
        val payload = TransferTestFixtures.payload()
        val localEvent = payload.snapshot.derivedMemoryEvents.last().copy(
            summary = "private-note.txt contains a secret body",
        )
        val invalid = payload.copy(
            snapshot = payload.snapshot.copy(
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.dropLast(1) + localEvent,
            ),
        )

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(invalid))
    }

    @Test
    fun `validator rejects invalid numeric place data`() {
        val payload = TransferTestFixtures.payload()
        val invalid = payload.copy(
            snapshot = payload.snapshot.copy(
                placeClusters = listOf(
                    payload.snapshot.placeClusters.single().copy(centroidLatitude = Double.NaN),
                ),
            ),
        )

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(invalid))
    }

    @Test
    fun `decoder rejects payloads larger than the v1 bound before parsing`() {
        val oversized = ByteArray(TransferBounds.MAX_PLAINTEXT_BYTES + 1)

        assertFailure(TransferFailureCode.TOO_LARGE, codec.decode(oversized))
    }

    @Test
    fun `empty input is an invalid payload`() {
        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(byteArrayOf()))
    }

    private fun <T> success(result: TransferResult<T>): T {
        assertTrue(result is TransferResult.Success)
        return (result as TransferResult.Success).value
    }

    private fun assertFailure(expected: TransferFailureCode, result: TransferResult<*>) {
        assertTrue(result is TransferResult.Failure)
        val actual = (result as TransferResult.Failure).failure.code
        assertEquals(expected, actual)
        assertNull(result.getOrNull())
    }
}

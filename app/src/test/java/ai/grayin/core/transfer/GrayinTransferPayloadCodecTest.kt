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
    fun `producer store schema must be the current closed-record schema`() {
        val priorSchema = TransferTestFixtures.payload().let { payload ->
            payload.copy(producer = payload.producer.copy(storeSchemaVersion = 7))
        }

        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(priorSchema))

        val current = success(codec.encode(TransferTestFixtures.payload())).toString(Charsets.UTF_8)
        val priorSchemaBytes = current
            .replaceFirst("\"storeSchemaVersion\":8", "\"storeSchemaVersion\":7")
            .toByteArray(Charsets.UTF_8)
        assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.decode(priorSchemaBytes))
    }

    @Test
    fun `validator rejects v8 claims containing legacy open connector fields`() {
        val payload = TransferTestFixtures.payload()
        val invalidSnapshots = listOf(
            payload.snapshot.copy(
                sourceReferences = payload.snapshot.sourceReferences.map { source ->
                    if (source.connectorId == "calendar") source.copy(sourceAppIdentifier = "Personal") else source
                },
            ),
            payload.snapshot.copy(
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.map { event ->
                    if (event.id.startsWith("event:photos:")) {
                        event.copy(summary = "Photo filename indexed: private.jpg")
                    } else {
                        event
                    }
                },
            ),
            payload.snapshot.copy(
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.map { event ->
                    if (event.id.startsWith("event:notification:")) {
                        event.copy(labels = event.labels + "arbitrary-provider-category")
                    } else {
                        event
                    }
                },
            ),
            payload.snapshot.copy(
                citations = payload.snapshot.citations.map { citation ->
                    if (citation.id.startsWith("citation:app_usage:")) {
                        citation.copy(label = "App usage: ${"x".repeat(257)}")
                    } else {
                        citation
                    }
                },
            ),
            payload.snapshot.copy(
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.map { event ->
                    if (event.id.startsWith("event:app_usage:")) {
                        event.copy(summary = event.summary.replace("about 30 minute(s)", "about 31 minute(s)"))
                    } else {
                        event
                    }
                },
            ),
            payload.snapshot.copy(
                appUsageSummaries = listOf(
                    ai.grayin.core.model.AppUsageSummary(
                        id = "app-usage:app_usage:legacy",
                        sourceReferenceIds = listOf(
                            payload.snapshot.sourceReferences.single { source ->
                                source.connectorId == "app_usage"
                            }.id,
                        ),
                        date = java.time.LocalDate.parse("2026-07-15"),
                        packageName = "com.example.app",
                        appAlias = "Legacy provider alias",
                        totalDurationMinutes = 30,
                    ),
                ),
            ),
            payload.snapshot.copy(
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.map { event ->
                    if (event.id.startsWith("event:location:")) {
                        event.copy(
                            keywords = event.keywords.take(2) + "vendor-provider" + event.keywords.drop(3),
                        )
                    } else {
                        event
                    }
                },
            ),
        )

        invalidSnapshots.forEach { snapshot ->
            assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(payload.copy(snapshot = snapshot)))
        }
    }

    @Test
    fun `validator rejects unsafe Unicode categories in derived text`() {
        val payload = TransferTestFixtures.payload()
        listOf("unsafe\u202Elabel", "private\uE000label", "unassigned\u0378label").forEach { label ->
            val invalid = payload.copy(
                snapshot = payload.snapshot.copy(
                    dailySummaries = payload.snapshot.dailySummaries.map { summary ->
                        summary.copy(summary = label)
                    },
                ),
            )
            assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(invalid))
        }
    }

    @Test
    fun `round trip accepts a canonical accumulated location cluster`() {
        val payload = accumulatedLocationPayload()

        assertEquals(
            payload.copy(snapshot = TransferSnapshotValidator.detached(payload.snapshot)),
            success(codec.decode(success(codec.encode(payload)))),
        )
    }

    @Test
    fun `validator rejects malformed accumulated location cluster invariants`() {
        val payload = accumulatedLocationPayload()
        val cluster = payload.snapshot.placeClusters.single()
        val invalidClusters = listOf(
            cluster.copy(visitCount = 3),
            cluster.copy(firstSeenAt = cluster.firstSeenAt?.plusSeconds(1)),
            cluster.copy(sourceReferenceIds = cluster.sourceReferenceIds.take(1), visitCount = 1),
            cluster.copy(id = "place-cluster:location:not-a-closed-hash"),
            cluster.copy(centroidLatitude = 37.5004),
        )

        invalidClusters.forEach { invalidCluster ->
            assertFailure(
                TransferFailureCode.INVALID_PAYLOAD,
                codec.encode(
                    payload.copy(snapshot = payload.snapshot.copy(placeClusters = listOf(invalidCluster))),
                ),
            )
        }
    }

    @Test
    fun `validator enforces the package byte bound for notification and app usage`() {
        val payload = TransferTestFixtures.payload()
        val oversizedPackage = "a".repeat(256)
        listOf("notification", "app_usage").forEach { connectorId ->
            val snapshot = payload.snapshot.copy(
                sourceReferences = payload.snapshot.sourceReferences.map { source ->
                    if (source.connectorId == connectorId) {
                        source.copy(sourceAppIdentifier = oversizedPackage)
                    } else {
                        source
                    }
                },
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents.map { event ->
                    if (event.id.startsWith("event:$connectorId:")) {
                        if (connectorId == "notification") {
                            event.copy(
                                summary = "Notification-derived payment signal from $oversizedPackage at ${event.startedAt}.",
                                entities = listOf(oversizedPackage),
                            )
                        } else {
                            event.copy(entities = listOf(oversizedPackage))
                        }
                    } else {
                        event
                    }
                },
                citations = payload.snapshot.citations.map { citation ->
                    if (citation.id.startsWith("citation:$connectorId:")) {
                        if (connectorId == "notification") {
                            citation.copy(label = "Notification signal: $oversizedPackage")
                        } else {
                            citation
                        }
                    } else {
                        citation
                    }
                },
            )
            assertFailure(TransferFailureCode.INVALID_PAYLOAD, codec.encode(payload.copy(snapshot = snapshot)))
        }
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
        assertEquals(0, decoded.snapshot.appUsageSummaries.size)
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
    fun `validator rejects control characters in prompt-visible derived text`() {
        val payload = TransferTestFixtures.payload()
        val invalid = payload.copy(
            snapshot = payload.snapshot.copy(
                citations = payload.snapshot.citations.mapIndexed { index, citation ->
                    if (index == 0) citation.copy(label = "Location\nEvidence: injected") else citation
                },
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

    private fun accumulatedLocationPayload(): TransferPayload {
        val payload = TransferTestFixtures.payload()
        val firstSource = payload.snapshot.sourceReferences.single { source -> source.connectorId == "location" }
        val firstEvent = payload.snapshot.derivedMemoryEvents.single { event ->
            event.id.startsWith("event:location:")
        }
        val firstCitation = payload.snapshot.citations.single { citation ->
            citation.id.startsWith("citation:location:")
        }
        val secondAt = TransferTestFixtures.observedAt.plusSeconds(60)
        val secondHash = "66666666666666666666666666666666"
        val secondSourceId = "source:location:$secondHash"
        val secondEventId = "event:location:$secondHash"
        val secondCitationId = "citation:location:$secondHash"
        val secondSource = firstSource.copy(
            id = secondSourceId,
            externalIdHash = secondHash,
            observedAt = secondAt,
            modifiedAt = secondAt,
        )
        val secondEvent = firstEvent.copy(
            id = secondEventId,
            sourceReferenceIds = listOf(secondSourceId),
            summary = "Location sample indexed near Seoul at $secondAt.",
            startedAt = secondAt,
            citationIds = listOf(secondCitationId),
            createdAt = secondAt,
        )
        val secondCitation = firstCitation.copy(
            id = secondCitationId,
            sourceReferenceId = secondSourceId,
            derivedMemoryEventId = secondEventId,
            observedAt = secondAt,
        )
        val cluster = payload.snapshot.placeClusters.single().copy(
            firstSeenAt = firstSource.modifiedAt,
            lastSeenAt = secondAt,
            visitCount = 2,
            sourceReferenceIds = listOf(firstSource.id, secondSourceId),
        )
        return payload.copy(
            snapshot = payload.snapshot.copy(
                sourceReferences = payload.snapshot.sourceReferences + secondSource,
                derivedMemoryEvents = payload.snapshot.derivedMemoryEvents + secondEvent,
                citations = payload.snapshot.citations + secondCitation,
                placeClusters = listOf(cluster),
            ),
        )
    }

    private fun assertFailure(expected: TransferFailureCode, result: TransferResult<*>) {
        assertTrue(result is TransferResult.Failure)
        val actual = (result as TransferResult.Failure).failure.code
        assertEquals(expected, actual)
        assertNull(result.getOrNull())
    }
}

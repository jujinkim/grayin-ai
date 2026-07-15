package ai.grayin.core.ai

import ai.grayin.core.artifact.ArtifactHttpClient
import ai.grayin.core.artifact.ArtifactHttpResponse
import ai.grayin.core.artifact.BoundedFixedEndpointDownloader
import ai.grayin.core.artifact.FixedEndpointDocumentSpec
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteModelManifestClientTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keyPair = p256KeyPair()

    @Test
    fun missingProductionConfigurationReturnsBeforeTransportOpen() = runBlocking {
        var openCount = 0
        val client = RemoteModelManifestClient(
            configuration = null,
            downloader = BoundedFixedEndpointDownloader(
                ArtifactHttpClient {
                    openCount += 1
                    error("A disabled production manifest must not open a connection.")
                },
            ),
            acceptanceStore = ModelManifestAcceptanceStore {
                error("A disabled production manifest must not reach durable acceptance.")
            },
        )

        val result = client.refresh()

        assertFalse(RemoteModelManifestConfiguration.configured)
        assertEquals(null, RemoteModelManifestConfiguration.endpointUrl)
        assertEquals(null, RemoteModelManifestConfiguration.publicKeyX509Base64)
        assertEquals(
            ModelManifestRefreshResult.Unavailable(ModelManifestRefreshFailureCode.NOT_CONFIGURED),
            result,
        )
        assertEquals(0, openCount)
    }

    @Test
    fun productionTrustRequiresBothFixedEndpointAndValidP256Key() {
        val encodedKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

        assertNull(
            RemoteModelManifestConfigurationValidator.validate(
                endpointUrl = "http://models.example.test/releases/manifest.json",
                publicKeyX509Base64 = encodedKey,
                expectedKeyId = KEY_ID,
                appVersionCode = 1,
                clock = clock,
            ),
        )
        assertNull(
            RemoteModelManifestConfigurationValidator.validate(
                endpointUrl = "https://models.example.test/releases/manifest.json",
                publicKeyX509Base64 = "not-base64!!",
                expectedKeyId = KEY_ID,
                appVersionCode = 1,
                clock = clock,
            ),
        )
        assertNotNull(
            RemoteModelManifestConfigurationValidator.validate(
                endpointUrl = "https://models.example.test/releases/manifest.json",
                publicKeyX509Base64 = encodedKey,
                expectedKeyId = KEY_ID,
                appVersionCode = 1,
                clock = clock,
            ),
        )
    }

    @Test
    fun invalidConfigurationReturnsBeforeTransportOpen() = runBlocking {
        var openCount = 0
        val client = RemoteModelManifestClient(
            configuration = null,
            configurationFailure = ModelManifestRefreshFailureCode.CONFIGURATION_INVALID,
            downloader = BoundedFixedEndpointDownloader(
                ArtifactHttpClient {
                    openCount += 1
                    error("Invalid trust configuration must not open a connection.")
                },
            ),
            acceptanceStore = ModelManifestAcceptanceStore {
                error("Invalid trust configuration must not reach durable acceptance.")
            },
        )

        assertEquals(
            ModelManifestRefreshResult.Unavailable(ModelManifestRefreshFailureCode.CONFIGURATION_INVALID),
            client.refresh(),
        )
        assertEquals(0, openCount)
    }

    @Test
    fun verifiedSupportedManifestIsDurablyActivated() = runBlocking {
        val envelope = signedEnvelope(validManifest())
        var accepted: ModelManifestVerificationResult.Verified? = null
        var openCount = 0
        val client = configuredClient(
            envelope = envelope,
            acceptanceStore = ModelManifestAcceptanceStore { candidate ->
                accepted = candidate
                ModelManifestAcceptanceDecision.ACCEPT_NEW
            },
            onOpen = { openCount += 1 },
        )

        val result = client.refresh()

        assertEquals(ModelManifestRefreshResult.Activated(sequence = 7L, replay = false), result)
        assertEquals(1, openCount)
        assertNotNull(accepted)
        assertEquals(ModelCatalog.GRAYIN_DEDICATED_MODEL_ID, accepted?.manifest?.models?.single()?.modelId)
    }

    @Test
    fun unsupportedCatalogIdentityIsRejectedBeforeDurableAcceptance() = runBlocking {
        val unsupported = validManifest().copy(
            models = listOf(validEntry().copy(modelId = "gemma-4-E2B-it")),
        )
        var acceptanceCount = 0
        val client = configuredClient(
            envelope = signedEnvelope(unsupported),
            acceptanceStore = ModelManifestAcceptanceStore {
                acceptanceCount += 1
                ModelManifestAcceptanceDecision.ACCEPT_NEW
            },
        )

        val result = client.refresh()

        assertEquals(
            ModelManifestRefreshResult.Unavailable(ModelManifestRefreshFailureCode.CATALOG_UNSUPPORTED),
            result,
        )
        assertEquals(0, acceptanceCount)
    }

    @Test
    fun durableRollbackDecisionIsReportedWithoutActivation() = runBlocking {
        val client = configuredClient(
            envelope = signedEnvelope(validManifest()),
            acceptanceStore = ModelManifestAcceptanceStore {
                ModelManifestAcceptanceDecision.REJECT_ROLLBACK
            },
        )

        assertEquals(
            ModelManifestRefreshResult.Unavailable(ModelManifestRefreshFailureCode.ROLLBACK_REJECTED),
            client.refresh(),
        )
    }

    @Test
    fun uiRefreshGateSuppressesRepeatedTransportWhileForcedRefreshRemainsAvailable() = runBlocking {
        var nowNanos = 1_000L
        var openCount = 0
        val client = configuredClient(
            envelope = signedEnvelope(validManifest()),
            acceptanceStore = ModelManifestAcceptanceStore {
                ModelManifestAcceptanceDecision.ACCEPT_REPLAY
            },
            onOpen = { openCount += 1 },
            monotonicNanos = { nowNanos },
        )

        assertNotNull(client.refreshIfDue(minimumIntervalNanos = 100L))
        assertNull(client.refreshIfDue(minimumIntervalNanos = 100L))
        assertEquals(1, openCount)

        client.refresh()
        assertEquals(2, openCount)

        nowNanos += 100L
        assertNotNull(client.refreshIfDue(minimumIntervalNanos = 100L))
        assertEquals(3, openCount)
    }

    @Test
    fun defaultUiRefreshGateIsSharedAcrossClientInstancesInOneProcess() = runBlocking {
        var nowNanos = 1_000L
        var firstOpenCount = 0
        var secondOpenCount = 0
        val first = configuredClient(
            envelope = signedEnvelope(validManifest()),
            acceptanceStore = ModelManifestAcceptanceStore {
                ModelManifestAcceptanceDecision.ACCEPT_REPLAY
            },
            onOpen = { firstOpenCount += 1 },
            monotonicNanos = { nowNanos },
            uiRefreshGate = null,
        )
        val recreated = configuredClient(
            envelope = signedEnvelope(validManifest()),
            acceptanceStore = ModelManifestAcceptanceStore {
                ModelManifestAcceptanceDecision.ACCEPT_REPLAY
            },
            onOpen = { secondOpenCount += 1 },
            monotonicNanos = { nowNanos },
            uiRefreshGate = null,
        )

        assertNotNull(first.refreshIfDue(minimumIntervalNanos = 100L))
        assertNull(recreated.refreshIfDue(minimumIntervalNanos = 100L))
        assertEquals(1, firstOpenCount)
        assertEquals(0, secondOpenCount)

        nowNanos += 100L
        assertNotNull(recreated.refreshIfDue(minimumIntervalNanos = 100L))
        assertEquals(1, secondOpenCount)
    }

    private fun configuredClient(
        envelope: ByteArray,
        acceptanceStore: ModelManifestAcceptanceStore,
        onOpen: () -> Unit = {},
        monotonicNanos: () -> Long = System::nanoTime,
        uiRefreshGate: ModelManifestRefreshGate? = ModelManifestRefreshGate(),
    ): RemoteModelManifestClient {
        val configuration = RemoteModelManifestClientConfiguration(
            endpoint = FixedEndpointDocumentSpec(
                id = "model-release-manifest",
                url = "https://models.example.test/releases/manifest.json",
                maximumSizeBytes = SignedModelManifestVerifier.MAX_ENVELOPE_BYTES,
                allowedContentTypes = setOf("application/json"),
                acceptContentType = "application/json",
            ),
            verifier = SignedModelManifestVerifier(
                expectedKeyId = KEY_ID,
                publicKeyX509 = keyPair.public.encoded,
                appVersionCode = 1,
                clock = clock,
            ),
        )
        val downloader = BoundedFixedEndpointDownloader(
            ArtifactHttpClient {
                onOpen()
                FakeResponse(envelope)
            },
        )
        return if (uiRefreshGate == null) {
            RemoteModelManifestClient(
                configuration = configuration,
                downloader = downloader,
                acceptanceStore = acceptanceStore,
                monotonicNanos = monotonicNanos,
            )
        } else {
            RemoteModelManifestClient(
                configuration = configuration,
                downloader = downloader,
                acceptanceStore = acceptanceStore,
                monotonicNanos = monotonicNanos,
                uiRefreshGate = uiRefreshGate,
            )
        }
    }

    private fun signedEnvelope(manifest: ModelReleaseManifest): ByteArray {
        val payload = SignedModelManifestCodec.canonicalPayloadBytes(manifest)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return SignedModelManifestCodec.canonicalEnvelopeBytes(
            SignedModelManifestEnvelope(
                keyId = KEY_ID,
                payload = encoder.encodeToString(payload),
                signature = encoder.encodeToString(signature),
            ),
        )
    }

    private fun validManifest(): ModelReleaseManifest {
        return ModelReleaseManifest(
            schemaVersion = SignedModelManifestVerifier.SUPPORTED_SCHEMA_VERSION,
            sequence = 7L,
            issuedAtEpochSeconds = now.minusSeconds(60L).epochSecond,
            expiresAtEpochSeconds = now.plusSeconds(24L * 60L * 60L).epochSecond,
            minimumAppVersionCode = 1,
            models = listOf(validEntry()),
        )
    }

    private fun validEntry(): ModelReleaseManifestEntry {
        return ModelReleaseManifestEntry(
            modelId = ModelCatalog.GRAYIN_DEDICATED_MODEL_ID,
            releaseVersion = "1.0.0",
            fileName = "grayin-gemma-4-E2B-it-wi8-afp32-v1.litertlm",
            downloadUrl = "https://models.example.test/releases/v1/model.litertlm",
            sizeBytes = SignedModelManifestVerifier.MIN_MODEL_BYTES,
            sha256 = "a".repeat(64),
            licenseUrl = "https://models.example.test/terms/v1",
            liteRtLmRuntimeVersion = SignedModelManifestVerifier.SUPPORTED_LITERT_LM_RUNTIME_VERSION,
            containerMajorVersion = SignedModelManifestVerifier.SUPPORTED_CONTAINER_MAJOR_VERSION,
            deprecated = false,
        )
    }

    private fun p256KeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private class FakeResponse(
        private val body: ByteArray,
    ) : ArtifactHttpResponse {
        override val statusCode: Int = 200
        override val contentLength: Long = body.size.toLong()
        override val contentType: String = "application/json; charset=utf-8"
        override val contentEncoding: String? = null
        override fun openBody(): InputStream = ByteArrayInputStream(body)

        override fun close() = Unit
    }

    private companion object {
        const val KEY_ID = "grayin-model-manifest-test-1"
    }
}

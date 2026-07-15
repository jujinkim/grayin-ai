package ai.grayin.core.ai

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedModelManifestTest {
    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keyPair = p256KeyPair()

    @Test
    fun validCanonicalP256EnvelopeIsAccepted() {
        val manifest = validManifest()
        assertTrue(verifier().trustConfigured)

        val result = verifier().verify(signedEnvelope(manifest))

        assertTrue(result is ModelManifestVerificationResult.Verified)
        result as ModelManifestVerificationResult.Verified
        assertEquals(7L, result.manifest.sequence)
        assertEquals(64, result.payloadSha256.length)
    }

    @Test
    fun tamperingWrongKeyAndNonCanonicalJsonFailClosed() {
        val valid = signedEnvelope(validManifest())
        val tampered = valid.copyOf().also { bytes ->
            val index = bytes.indexOf('A'.code.toByte()).takeIf { it >= 0 } ?: bytes.lastIndex
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
        }
        assertTrue(verifier().verify(tampered) is ModelManifestVerificationResult.Rejected)

        val wrongKeyVerifier = verifier(p256KeyPair())
        assertRejected(wrongKeyVerifier.verify(valid), ModelManifestRejectionCode.SIGNATURE_INVALID)

        val nonCanonical = " ${valid.decodeToString()}".encodeToByteArray()
        assertRejected(verifier().verify(nonCanonical), ModelManifestRejectionCode.ENVELOPE_INVALID)
    }

    @Test
    fun unsupportedCompatibilityAndUnsafeArtifactsAreRejected() {
        assertRejected(
            verifier().verify(signedEnvelope(validManifest().copy(minimumAppVersionCode = 2))),
            ModelManifestRejectionCode.APP_VERSION_UNSUPPORTED,
        )
        val unsafe = validEntry().copy(downloadUrl = "https://models.example.test/model.litertlm?device=1")
        assertRejected(
            verifier().verify(signedEnvelope(validManifest().copy(models = listOf(unsafe)))),
            ModelManifestRejectionCode.ENTRY_INVALID,
        )
        val wrongContainer = validEntry().copy(containerMajorVersion = 2)
        assertRejected(
            verifier().verify(signedEnvelope(validManifest().copy(models = listOf(wrongContainer)))),
            ModelManifestRejectionCode.ENTRY_INVALID,
        )
        val wrongRuntime = validEntry().copy(liteRtLmRuntimeVersion = "0.14.0")
        assertRejected(
            verifier().verify(signedEnvelope(validManifest().copy(models = listOf(wrongRuntime)))),
            ModelManifestRejectionCode.ENTRY_INVALID,
        )
    }

    @Test
    fun validityWindowAndBoundsAreEnforced() {
        assertRejected(
            verifier().verify(
                signedEnvelope(validManifest().copy(issuedAtEpochSeconds = now.epochSecond + 301)),
            ),
            ModelManifestRejectionCode.NOT_YET_VALID,
        )
        assertRejected(
            verifier().verify(
                signedEnvelope(
                    validManifest().copy(
                        issuedAtEpochSeconds = now.epochSecond - 1_000,
                        expiresAtEpochSeconds = now.epochSecond - 301,
                    ),
                ),
            ),
            ModelManifestRejectionCode.EXPIRED,
        )
        assertRejected(
            verifier().verify(ByteArray(SignedModelManifestVerifier.MAX_ENVELOPE_BYTES + 1)),
            ModelManifestRejectionCode.ENVELOPE_TOO_LARGE,
        )
    }

    @Test
    fun rollbackPolicyRejectsOlderAndSameSequenceEquivocation() {
        val candidate = verified(validManifest())
        val current = AcceptedModelManifestState(7L, candidate.payloadSha256, candidate.trustIdentity)

        assertEquals(
            ModelManifestAcceptanceDecision.ACCEPT_REPLAY,
            ModelManifestRollbackPolicy.decide(current, candidate),
        )
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_ROLLBACK,
            ModelManifestRollbackPolicy.decide(
                current,
                verified(validManifest().copy(sequence = 6L)),
            ),
        )
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_EQUIVOCATION,
            ModelManifestRollbackPolicy.decide(
                current,
                verified(validManifest().copy(models = listOf(validEntry().copy(deprecated = true)))),
            ),
        )
        assertEquals(
            ModelManifestAcceptanceDecision.ACCEPT_NEW,
            ModelManifestRollbackPolicy.decide(
                current,
                verified(validManifest().copy(sequence = 8L)),
            ),
        )

        val rotatedKey = p256KeyPair()
        val rotated = verified(validManifest().copy(sequence = 1L), rotatedKey)
        assertTrue(rotated.trustIdentity != current.trustIdentity)
        assertEquals(
            ModelManifestAcceptanceDecision.ACCEPT_NEW,
            ModelManifestRollbackPolicy.decide(current, rotated),
        )
    }

    @Test
    fun productionManifestActivationHasNoPlaceholderTrustMaterial() {
        assertFalse(RemoteModelManifestConfiguration.configured)
        assertEquals(null, RemoteModelManifestConfiguration.endpointUrl)
        assertEquals(null, RemoteModelManifestConfiguration.publicKeyX509Base64)
    }

    @Test
    fun canonicalPayloadMatchesReleaseToolGoldenFixture() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("model_manifest_payload_v1.json"))
            .use { it.readBytes() }
            .dropLastWhile { byte -> byte == '\n'.code.toByte() }
            .toByteArray()
        val manifest = validManifest().copy(
            issuedAtEpochSeconds = 1_784_073_600L,
            expiresAtEpochSeconds = 1_784_160_000L,
            models = listOf(
                validEntry().copy(
                    sizeBytes = 1_048_576L,
                    sha256 = "78a0945aad939323f925d138e7090ba00539e07c101c78d8826e11abb51df758",
                ),
            ),
        )

        assertTrue(SignedModelManifestCodec.canonicalPayloadBytes(manifest).contentEquals(fixture))
        assertEquals(manifest, SignedModelManifestCodec.decodeCanonicalPayload(fixture))
    }

    private fun verifier(signingKey: KeyPair = keyPair): SignedModelManifestVerifier {
        return SignedModelManifestVerifier(
            expectedKeyId = KEY_ID,
            publicKeyX509 = signingKey.public.encoded,
            appVersionCode = 1,
            clock = clock,
        )
    }

    private fun verified(
        manifest: ModelReleaseManifest,
        signingKey: KeyPair = keyPair,
    ): ModelManifestVerificationResult.Verified {
        return verifier(signingKey).verify(signedEnvelope(manifest, signingKey)) as
            ModelManifestVerificationResult.Verified
    }

    private fun signedEnvelope(
        manifest: ModelReleaseManifest,
        signingKey: KeyPair = keyPair,
    ): ByteArray {
        val payload = SignedModelManifestCodec.canonicalPayloadBytes(manifest)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey.private)
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
            schemaVersion = 1,
            sequence = 7L,
            issuedAtEpochSeconds = now.minusSeconds(60).epochSecond,
            expiresAtEpochSeconds = now.plusSeconds(24 * 60 * 60).epochSecond,
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
            sizeBytes = 2_300_000_000L,
            sha256 = "a".repeat(64),
            licenseUrl = "https://models.example.test/terms/v1",
            liteRtLmRuntimeVersion = "0.13.1",
            containerMajorVersion = 1,
            deprecated = false,
        )
    }

    private fun p256KeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun assertRejected(result: ModelManifestVerificationResult, code: ModelManifestRejectionCode) {
        assertTrue(result is ModelManifestVerificationResult.Rejected)
        assertEquals(code, (result as ModelManifestVerificationResult.Rejected).code)
    }

    private companion object {
        const val KEY_ID = "grayin-model-manifest-test-1"
    }
}

package ai.grayin.core.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class ModelManifestStateStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: ModelManifestStateStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(ModelManifestStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = ModelManifestStateStore(context)
    }

    @Test
    fun acceptedSequenceIsDurableAndRejectsRollbackOrEquivocation() {
        val first = candidate(sequence = 7L)

        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_NEW, store.accept(first))
        assertEquals(
            StoredModelManifestState.Accepted(
                AcceptedModelManifestState(7L, first.payloadSha256, first.trustIdentity),
            ),
            ModelManifestStateStore(context).readState(),
        )
        assertEquals(
            first.manifest,
            ModelManifestStateStore(context).readAcceptedManifest(first.trustIdentity),
        )
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_REPLAY, store.accept(first))
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_ROLLBACK,
            store.accept(candidate(sequence = 6L)),
        )
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_EQUIVOCATION,
            store.accept(candidate(sequence = 7L, issuedAtEpochSeconds = 2L)),
        )
    }

    @Test
    fun partialOrMalformedStoredStateFailsClosed() {
        val prefs = context.getSharedPreferences(ModelManifestStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(ModelManifestStateStore.KEY_SEQUENCE, 7L).commit()

        assertEquals(StoredModelManifestState.Invalid, store.readState())
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_STATE_INVALID,
            store.accept(candidate(sequence = 8L)),
        )
    }

    @Test
    fun corruptPersistedCanonicalPayloadFailsClosed() {
        val accepted = candidate(sequence = 7L)
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_NEW, store.accept(accepted))
        context.getSharedPreferences(ModelManifestStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ModelManifestStateStore.KEY_CANONICAL_PAYLOAD, "{}")
            .commit()

        assertEquals(StoredModelManifestState.Invalid, store.readState())
        assertNull(store.readAcceptedManifest(accepted.trustIdentity))
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_STATE_INVALID,
            store.accept(candidate(sequence = 8L)),
        )
    }

    @Test
    fun replayBackfillsPayloadWhenRollbackAndTrustStateAlreadyExist() {
        val accepted = candidate(sequence = 7L)
        context.getSharedPreferences(ModelManifestStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(ModelManifestStateStore.KEY_SEQUENCE, accepted.manifest.sequence)
            .putString(ModelManifestStateStore.KEY_PAYLOAD_SHA256, accepted.payloadSha256)
            .putString(ModelManifestStateStore.KEY_TRUST_KEY_ID, accepted.trustIdentity.keyId)
            .putString(
                ModelManifestStateStore.KEY_TRUST_PUBLIC_KEY_SHA256,
                accepted.trustIdentity.publicKeySha256,
            )
            .commit()

        assertNull(store.readAcceptedManifest(accepted.trustIdentity))
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_REPLAY, store.accept(accepted))
        assertEquals(accepted.manifest, store.readAcceptedManifest(accepted.trustIdentity))
    }

    @Test
    fun keyRotationHidesOldPayloadUntilNewTrustAcceptsItsOwnManifest() {
        val first = candidate(sequence = 7L, trustIdentity = TRUST_A)
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_NEW, store.accept(first))
        assertEquals(first.manifest, store.readAcceptedManifest(TRUST_A))
        assertNull(store.readAcceptedManifest(TRUST_B))

        val rotated = candidate(
            sequence = 1L,
            issuedAtEpochSeconds = 3L,
            trustIdentity = TRUST_B,
        )
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_NEW, store.accept(rotated))
        assertNull(store.readAcceptedManifest(TRUST_A))
        assertEquals(rotated.manifest, store.readAcceptedManifest(TRUST_B))
    }

    private fun candidate(
        sequence: Long,
        issuedAtEpochSeconds: Long = 1L,
        trustIdentity: ModelManifestTrustIdentity = TRUST_A,
    ): ModelManifestVerificationResult.Verified {
        val manifest = ModelReleaseManifest(
            schemaVersion = 1,
            sequence = sequence,
            issuedAtEpochSeconds = issuedAtEpochSeconds,
            expiresAtEpochSeconds = issuedAtEpochSeconds + 1L,
            minimumAppVersionCode = 1,
            models = emptyList(),
        )
        val payload = SignedModelManifestCodec.canonicalPayloadBytes(manifest)
        return ModelManifestVerificationResult.Verified(
            manifest = manifest,
            payloadSha256 = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString(separator = "") { byte -> "%02x".format(byte) },
            trustIdentity = trustIdentity,
        )
    }

    private companion object {
        val TRUST_A = ModelManifestTrustIdentity("test-key-a", "a".repeat(64))
        val TRUST_B = ModelManifestTrustIdentity("test-key-b", "b".repeat(64))
    }
}

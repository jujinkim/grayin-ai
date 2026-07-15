package ai.grayin.core.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        val first = candidate(sequence = 7L, digest = "a".repeat(64))

        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_NEW, store.accept(first))
        assertEquals(
            StoredModelManifestState.Accepted(AcceptedModelManifestState(7L, "a".repeat(64))),
            ModelManifestStateStore(context).readState(),
        )
        assertEquals(ModelManifestAcceptanceDecision.ACCEPT_REPLAY, store.accept(first))
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_ROLLBACK,
            store.accept(candidate(sequence = 6L, digest = "b".repeat(64))),
        )
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_EQUIVOCATION,
            store.accept(candidate(sequence = 7L, digest = "b".repeat(64))),
        )
    }

    @Test
    fun partialOrMalformedStoredStateFailsClosed() {
        val prefs = context.getSharedPreferences(ModelManifestStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(ModelManifestStateStore.KEY_SEQUENCE, 7L).commit()

        assertEquals(StoredModelManifestState.Invalid, store.readState())
        assertEquals(
            ModelManifestAcceptanceDecision.REJECT_STATE_INVALID,
            store.accept(candidate(sequence = 8L, digest = "c".repeat(64))),
        )
    }

    private fun candidate(sequence: Long, digest: String): ModelManifestVerificationResult.Verified {
        return ModelManifestVerificationResult.Verified(
            manifest = ModelReleaseManifest(
                schemaVersion = 1,
                sequence = sequence,
                issuedAtEpochSeconds = 1L,
                expiresAtEpochSeconds = 2L,
                minimumAppVersionCode = 1,
                models = emptyList(),
            ),
            payloadSha256 = digest,
        )
    }
}

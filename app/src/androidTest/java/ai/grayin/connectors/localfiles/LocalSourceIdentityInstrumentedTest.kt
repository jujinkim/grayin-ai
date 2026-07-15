package ai.grayin.connectors.localfiles

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.security.AndroidKeystoreSourceIdentityHasher
import ai.grayin.core.security.SourceIdentityHasher
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSourceIdentityInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearConnectorPreferences() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun keystoreHmacIsStableSeparatedAndNonExtractable() {
        val firstHasher = AndroidKeystoreSourceIdentityHasher()
        val first = firstHasher.hmac("local-document-v1", "content://provider/document/one")
        val reopened = AndroidKeystoreSourceIdentityHasher()

        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertEquals(first, reopened.hmac("local-document-v1", "content://provider/document/one"))
        assertFalse(first == reopened.hmac("local-pdf-page-v1", "content://provider/document/one"))
        assertFalse(first == reopened.hmac("local-document-v1", "content://provider/document/two"))

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretKey = checkNotNull(keyStore.getKey(KEY_ALIAS, null))
        assertNull(secretKey.encoded)
    }

    @Test
    fun legacyRawUriPreferenceMigratesToHmacOnlyReadiness() = runBlocking {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putStringSet(KEY_LEGACY_SELECTED_URIS, setOf("content://provider/private/secret-note.md"))
            .putString(KEY_LAST_INDEXED_AT, "2026-07-15T00:00:00Z")
            .commit()

        val state = LocalFilesConnector(context).currentState()

        assertTrue(state.enabled)
        assertTrue(state.permissionGranted)
        assertFalse(preferences.contains(KEY_LEGACY_SELECTED_URIS))
        assertFalse(preferences.contains(KEY_LAST_INDEXED_AT))
        val markers = preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet()).orEmpty()
        assertEquals(1, markers.size)
        assertTrue(markers.single().matches(Regex("[a-f0-9]{64}")))
        assertFalse(preferences.all.values.any { value -> value.toString().contains("secret-note.md") })
    }

    @Test
    fun safCallCancellationSignalsBlockedProviderWorkWithoutWaitingForIt() = runBlocking {
        val cancellationObserved = AtomicBoolean(false)
        val startedAt = System.nanoTime()

        try {
            withTimeout(50L) {
                runCancellableSafCall<Unit> { cancellationSignal ->
                    while (!cancellationSignal.isCanceled) Thread.sleep(1L)
                    cancellationObserved.set(true)
                }
            }
            throw AssertionError("Expected the SAF call to time out.")
        } catch (_: TimeoutCancellationException) {
        }

        val observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (!cancellationObserved.get() && System.nanoTime() < observationDeadline) {
            Thread.sleep(1L)
        }
        assertTrue(cancellationObserved.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000L)
    }

    @Test
    fun canceledTextReadClosesTheUnderlyingPipeDescriptor() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val readerFinished = AtomicBoolean(false)

        try {
            withTimeout(200L) {
                runCancellableSafCall<BoundedLocalText> { cancellationSignal ->
                    try {
                        readBoundedUtf8(
                            descriptor = AssetFileDescriptor(
                                readSide,
                                0L,
                                AssetFileDescriptor.UNKNOWN_LENGTH,
                            ),
                            cancellationSignal = cancellationSignal,
                            maxChars = 64,
                        )
                    } finally {
                        readerFinished.set(true)
                    }
                }
            }
            throw AssertionError("Expected the blocking pipe read to time out.")
        } catch (_: TimeoutCancellationException) {
        }

        val finishDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (!readerFinished.get() && System.nanoTime() < finishDeadline) {
            Thread.sleep(1L)
        }
        assertTrue(readerFinished.get())
        assertThrows(IOException::class.java) {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                output.write(1)
                output.flush()
            }
        }
    }

    @Test
    fun selectionLimitIsCheckedBeforeTakingAnotherGrant() = runBlocking {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingMarkers = (0 until LocalFilesConnector.MAX_SELECTED_DOCUMENTS)
            .mapTo(mutableSetOf()) { index -> "%064x".format(index) }
        preferences.edit().putStringSet(KEY_SELECTED_IDENTITY_HMACS, existingMarkers).commit()
        val permissionStore = FakePermissionStore()
        val connector = connector(
            permissionStore = permissionStore,
            identityHasher = SourceIdentityHasher { _, _ -> "f".repeat(64) },
        )

        assertFalse(connector.rememberSelectedFile(Uri.parse("content://provider/new.txt")))
        assertEquals(0, permissionStore.persistCalls.get())
        assertEquals(existingMarkers, preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet()))
    }

    @Test
    fun failedGrantAcquisitionRollsBackTheNewMarker() = runBlocking {
        val permissionStore = FakePermissionStore(failPersist = true)
        val connector = connector(permissionStore)

        assertFalse(connector.rememberSelectedFile(Uri.parse("content://provider/new.txt")))
        assertTrue(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet())
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun revokeReleasesEveryAppOwnedReadGrantIncludingUnmarkedOnes() = runBlocking {
        val marked = Uri.parse("content://provider/marked.txt")
        val unmarked = Uri.parse("content://provider/unmarked.pdf")
        val permissionStore = FakePermissionStore(initialGrants = setOf(marked, unmarked))
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putStringSet(KEY_SELECTED_IDENTITY_HMACS, setOf("a".repeat(64)))
            .commit()
        val connector = connector(permissionStore)

        connector.revoke()

        assertTrue(permissionStore.persistedReadUris().isEmpty())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun revokeFailureKeepsMarkersAndPreventsDerivedDeletionRequest() {
        val blocked = Uri.parse("content://provider/blocked.pdf")
        val permissionStore = FakePermissionStore(
            initialGrants = setOf(blocked),
            releaseFailures = setOf(blocked),
        )
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val marker = "b".repeat(64)
        preferences.edit().putStringSet(KEY_SELECTED_IDENTITY_HMACS, setOf(marker)).commit()
        val connector = connector(permissionStore)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { connector.revoke() }
        }

        assertEquals(setOf(marker), preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet()))
        assertEquals(listOf(blocked), permissionStore.persistedReadUris())
    }

    @Test
    fun hmacFailureAbortsScanAndPreservesTheSelectionMarker() {
        val uri = Uri.parse("content://provider/private.pdf")
        val permissionStore = FakePermissionStore(initialGrants = setOf(uri))
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val marker = "c".repeat(64)
        preferences.edit().putStringSet(KEY_SELECTED_IDENTITY_HMACS, setOf(marker)).commit()
        val connector = connector(
            permissionStore = permissionStore,
            identityHasher = SourceIdentityHasher { _, _ -> error("keystore unavailable") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { connector.scan(ConnectorScanScope()) }
        }

        assertEquals(setOf(marker), preferences.getStringSet(KEY_SELECTED_IDENTITY_HMACS, emptySet()))
    }

    @Test
    fun hmacFailureDoesNotEraseLegacyMigrationInput() {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawUri = "content://provider/private/legacy.txt"
        preferences.edit()
            .putStringSet(KEY_LEGACY_SELECTED_URIS, setOf(rawUri))
            .putString(KEY_LAST_INDEXED_AT, "2026-07-15T00:00:00Z")
            .commit()
        val connector = connector(
            permissionStore = FakePermissionStore(),
            identityHasher = SourceIdentityHasher { _, _ -> error("keystore unavailable") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { connector.currentState() }
        }

        assertEquals(setOf(rawUri), preferences.getStringSet(KEY_LEGACY_SELECTED_URIS, emptySet()))
        assertTrue(preferences.contains(KEY_LAST_INDEXED_AT))
    }

    private fun connector(
        permissionStore: LocalDocumentPermissionStore,
        identityHasher: SourceIdentityHasher = SourceIdentityHasher { _, _ -> "d".repeat(64) },
    ): LocalFilesConnector {
        return LocalFilesConnector(
            context = context,
            identityHasher = identityHasher,
            permissionStore = permissionStore,
        )
    }

    private class FakePermissionStore(
        initialGrants: Set<Uri> = emptySet(),
        private val failPersist: Boolean = false,
        private val releaseFailures: Set<Uri> = emptySet(),
    ) : LocalDocumentPermissionStore {
        private val grants = initialGrants.toMutableSet()
        val persistCalls = AtomicInteger(0)

        override fun persistRead(uri: Uri) {
            persistCalls.incrementAndGet()
            if (failPersist) throw SecurityException("denied")
            grants += uri
        }

        override fun releaseRead(uri: Uri) {
            if (uri in releaseFailures) throw SecurityException("denied")
            grants -= uri
        }

        override fun persistedReadUris(): List<Uri> = grants.sortedBy(Uri::toString)
    }

    private companion object {
        const val PREFS_NAME = "grayin_local_files"
        const val KEY_LEGACY_SELECTED_URIS = "selected_uris"
        const val KEY_SELECTED_IDENTITY_HMACS = "selected_source_hmacs_v1"
        const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        const val KEY_ALIAS = "grayin_source_identity_hmac_v1"
    }
}
